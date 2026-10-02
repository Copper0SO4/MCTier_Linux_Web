use super::{InputDevice, Reply};
use std::{
    collections::VecDeque,
    sync::{
        atomic::{AtomicBool, Ordering},
        mpsc::{Receiver, TryRecvError},
        Arc,
    },
    time::{Duration, Instant},
};
use windows::{
    core::{Interface, PCWSTR},
    Win32::{Media::Audio::*, System::Com::*},
};

struct Apartment;
impl Apartment {
    fn new() -> Result<Self, String> {
        unsafe {
            CoInitializeEx(None, COINIT_MULTITHREADED)
                .ok()
                .map_err(|e| e.to_string())?;
        }
        Ok(Self)
    }
}
impl Drop for Apartment {
    fn drop(&mut self) {
        unsafe {
            CoUninitialize();
        }
    }
}
pub fn devices() -> Result<Vec<InputDevice>, String> {
    let _apartment = Apartment::new()?;
    let enumerator = wasapi::DeviceEnumerator::new().map_err(|e| e.to_string())?;
    let default_id = enumerator.get_default_device_for_role(&wasapi::Direction::Capture, &wasapi::Role::Console).and_then(|device| device.get_id()).ok();
    let communications_id = enumerator.get_default_device_for_role(&wasapi::Direction::Capture, &wasapi::Role::Communications).and_then(|device| device.get_id()).ok();
    let collection = enumerator
        .get_device_collection(&wasapi::Direction::Capture)
        .map_err(|e| e.to_string())?;
    let mut devices = Vec::new();
    for index in 0..collection.get_nbr_devices().map_err(|e| e.to_string())? {
        let Ok(device) = collection.get_device_at_index(index) else {
            continue;
        };
        if let (Ok(id), Ok(label)) = (device.get_id(), device.get_friendlyname()) {
            devices.push(InputDevice {
                device_id: format!("wasapi:{id}"),
                label,
                kind: "audioinput",
                is_default: default_id.as_ref() == Some(&id),
                is_communications: communications_id.as_ref() == Some(&id),
            });
        }
    }
    Ok(devices)
}
const CHUNK: usize = 960 * 4;
const CAPACITY: usize = CHUNK * 5;
fn push_packet(queue: &mut VecDeque<u8>, bytes: &[u8]) {
    // Drop old samples on a stalled consumer rather than accumulating seconds of speech.
    queue.extend(bytes);
    if queue.len() > CAPACITY {
        queue.drain(..queue.len() - CAPACITY);
    }
}
struct Running(IAudioClient);
impl Drop for Running {
    fn drop(&mut self) {
        unsafe {
            let _ = self.0.Stop();
        }
    }
}

pub fn run(
    device_id: String,
    system_processing: bool,
    stop: Arc<AtomicBool>,
    requests: Receiver<Reply>,
    ready: tokio::sync::oneshot::Sender<Result<String, String>>,
) -> Result<(), String> {
    let mut ready = Some(ready);
    let result = (|| -> Result<(), String> {
        let _apartment = Apartment::new()?;
        unsafe {
            let enumerator: IMMDeviceEnumerator =
                CoCreateInstance(&MMDeviceEnumerator, None, CLSCTX_ALL)
                    .map_err(|e| e.to_string())?;
            // Legacy browser IDs are not WASAPI endpoint IDs: migrate them to system default.
            let device = if let Some(id) = device_id.strip_prefix("wasapi:") {
                let wide: Vec<u16> = id.encode_utf16().chain(Some(0)).collect();
                enumerator.GetDevice(PCWSTR(wide.as_ptr()))
            } else {
                // Match Chromium's "default" input and the UI's system-default label.
                // The communications endpoint can be a disconnected virtual mixer bus.
                enumerator.GetDefaultAudioEndpoint(eCapture, if device_id == "communications" { eCommunications } else { eConsole })
            }
            .map_err(|e| format!("MIC_NOT_FOUND:无法打开麦克风，请检查设备是否已连接: {e}"))?;
            // Prevent an untrusted endpoint ID from selecting loopback/output audio.
            let endpoint: IMMEndpoint = device.cast().map_err(|e| e.to_string())?;
            if endpoint.GetDataFlow().map_err(|e| e.to_string())? != eCapture {
                return Err("所选设备不是麦克风".into());
            }
            let native_id = device.GetId().map_err(|e| e.to_string())?;
            let actual_id = native_id.to_string().map_err(|e| e.to_string());
            CoTaskMemFree(Some(native_id.0.cast()));
            let actual_id = format!("wasapi:{}", actual_id?);
            let client: IAudioClient = device
                .Activate(CLSCTX_ALL, None)
                .map_err(|e| e.to_string())?;
            if let Ok(client2) = client.cast::<IAudioClient2>() {
                let properties = AudioClientProperties {
                    cbSize: std::mem::size_of::<AudioClientProperties>() as u32,
                    eCategory: if system_processing {
                        AudioCategory_Communications
                    } else {
                        AudioCategory_Media
                    },
                    ..Default::default()
                };
                if let Err(e) = client2.SetClientProperties(&properties) {
                    log::warn!("设备不支持通信音效: {e}");
                }
            }
            let format = WAVEFORMATEX {
                wFormatTag: 3,
                nChannels: 1,
                nSamplesPerSec: 48000,
                nAvgBytesPerSec: 192000,
                nBlockAlign: 4,
                wBitsPerSample: 32,
                cbSize: 0,
            };
            client
                .Initialize(
                    AUDCLNT_SHAREMODE_SHARED,
                    AUDCLNT_STREAMFLAGS_AUTOCONVERTPCM | AUDCLNT_STREAMFLAGS_SRC_DEFAULT_QUALITY,
                    200_000,
                    0,
                    &format,
                    None,
                )
                .map_err(|e| {
                    format!("麦克风启动失败，请检查 Windows 麦克风隐私设置及设备占用: {e}")
                })?;
            let capture: IAudioCaptureClient = client.GetService().map_err(|e| e.to_string())?;
            client.Start().map_err(|e| e.to_string())?;
            let _running = Running(client);
            if ready.take().unwrap().send(Ok(actual_id)).is_err() {
                return Ok(());
            }
            let mut queue = VecDeque::with_capacity(CAPACITY);
            let mut pending: Option<Reply> = None;
            let mut last_request = Instant::now();
            while !stop.load(Ordering::Acquire) && last_request.elapsed() < Duration::from_secs(5) {
                if pending.is_none() {
                    match requests.try_recv() {
                        Ok(reply) => {
                            pending = Some(reply);
                            last_request = Instant::now();
                        }
                        Err(TryRecvError::Disconnected) => break,
                        Err(TryRecvError::Empty) => {}
                    }
                }
                // Drain only a bounded number per pass so Stop cannot starve.
                for _ in 0..16 {
                    if capture.GetNextPacketSize().map_err(|e| e.to_string())? == 0 {
                        break;
                    }
                    let (mut ptr, mut frames, mut flags) = (std::ptr::null_mut(), 0u32, 0u32);
                    capture
                        .GetBuffer(&mut ptr, &mut frames, &mut flags, None, None)
                        .map_err(|e| e.to_string())?;
                    let size = frames as usize * 4;
                    // WASAPI may return a null pointer for silent packets. Never dereference it.
                    if flags & AUDCLNT_BUFFERFLAGS_SILENT.0 as u32 != 0 {
                        push_packet(&mut queue, &vec![0; size.min(CAPACITY)]);
                    } else if !ptr.is_null() && size <= 192000 {
                        push_packet(&mut queue, std::slice::from_raw_parts(ptr, size));
                    } else {
                        let _ = capture.ReleaseBuffer(frames);
                        return Err("麦克风返回了无效音频数据".into());
                    }
                    capture.ReleaseBuffer(frames).map_err(|e| e.to_string())?;
                }
                if pending.is_some() && queue.len() >= CHUNK {
                    let bytes = queue.drain(..CHUNK).collect();
                    let _ = pending.take().unwrap().send(Ok(bytes));
                }
                std::thread::sleep(Duration::from_millis(3));
            }
            Ok(())
        }
    })();
    if let Some(ready) = ready {
        let _ = ready.send(Err(result
            .as_ref()
            .err()
            .cloned()
            .unwrap_or_else(|| "麦克风启动失败".into())));
    }
    result
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn slow_consumer_keeps_only_latest_aligned_audio() {
        let mut queue = VecDeque::new();
        push_packet(&mut queue, &vec![1; CHUNK * 4]);
        push_packet(&mut queue, &vec![2; CHUNK * 3]);
        assert_eq!(queue.len(), CAPACITY);
        assert!(queue.iter().take(CHUNK * 2).all(|b| *b == 1));
        assert!(queue.iter().skip(CHUNK * 2).all(|b| *b == 2));
    }
    #[test]
    #[ignore = "Opens the default microphone briefly; requires interactive Windows audio hardware"]
    fn default_device_captures_pcm_and_releases_on_repeated_stop() {
        let inputs = devices().expect("enumerate capture endpoints");
        assert!(!inputs.is_empty(), "No active microphones");
        let expected = inputs.iter().find(|device| device.is_default).expect("system default microphone").device_id.clone();
        println!("Available native inputs: {}", serde_json::to_string(&inputs).unwrap());
        { let _com = Apartment::new().unwrap();
          let devices = wasapi::DeviceEnumerator::new().unwrap();
          for direction in [wasapi::Direction::Capture, wasapi::Direction::Render] {
            for role in [wasapi::Role::Console, wasapi::Role::Multimedia, wasapi::Role::Communications] {
              if let Ok(device) = devices.get_default_device_for_role(&direction, &role) { println!("Default {direction:?}/{role:?}: {}", device.get_friendlyname().unwrap_or_default()); }
            }
          }
        }
        for _ in 0..2 {
            let stop = Arc::new(AtomicBool::new(false));
            let worker_stop = stop.clone();
            let (tx, rx) = std::sync::mpsc::sync_channel(1);
            let (ready_tx, ready_rx) = tokio::sync::oneshot::channel();
            let worker =
                std::thread::spawn(move || run(String::new(), true, worker_stop, rx, ready_tx));
            let started = ready_rx.blocking_recv().expect("worker start response");
            if let Err(ref error) = started {
                stop.store(true, Ordering::Release);
                let _ = worker.join();
                panic!("{error}");
            }
            let actual = started.unwrap();
            println!("Default native input: {actual}");
            let mut peak = 0f32;
            for _ in 0..50 {
                let (reply, response) = tokio::sync::oneshot::channel();
                tx.send(reply).unwrap();
                let packet = response.blocking_recv().unwrap().unwrap();
                assert_eq!(packet.len(), CHUNK);
                assert!(packet
                    .chunks_exact(4)
                    .all(|b| f32::from_le_bytes(b.try_into().unwrap()).is_finite()));
                for sample in packet.chunks_exact(4) { peak = peak.max(f32::from_le_bytes(sample.try_into().unwrap()).abs()); }
            }
            println!("Native sample peak over 1 second: {peak}");
            stop.store(true, Ordering::Release);
            drop(tx);
            worker.join().expect("worker join").expect("capture stop");
            assert_eq!(actual, expected, "Default capture must use the console endpoint, not the communications endpoint");
        }
    }
}
