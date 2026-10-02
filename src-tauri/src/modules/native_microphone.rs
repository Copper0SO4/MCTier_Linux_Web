//! User-initiated WASAPI input, delivered as bounded 20 ms mono float32 packets.
use serde::Serialize;
use std::{
    collections::HashMap,
    sync::{
        atomic::{AtomicBool, Ordering},
        Arc, Mutex, OnceLock,
    },
    time::Duration,
};
use tauri::WebviewWindow;

#[derive(Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct InputDevice {
    pub device_id: String,
    pub label: String,
    pub kind: &'static str,
    pub is_default: bool,
    pub is_communications: bool,
}
#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Capture {
    id: String,
    device_id: String,
    sample_rate: u32,
}
type Reply = tokio::sync::oneshot::Sender<Result<Vec<u8>, String>>;
struct Session {
    stop: Arc<AtomicBool>,
    requests: std::sync::mpsc::SyncSender<Reply>,
}
fn sessions() -> &'static Mutex<HashMap<String, Session>> {
    static SESSIONS: OnceLock<Mutex<HashMap<String, Session>>> = OnceLock::new();
    SESSIONS.get_or_init(Default::default)
}
fn authorize(window: &WebviewWindow) -> Result<(), String> {
    let url = window.url().map_err(|e| e.to_string())?;
    if window.label() != "main" || !crate::modules::media_permission::trusted(url.as_str()) {
        return Err("仅 MCTier 主窗口可操作麦克风".into());
    }
    Ok(())
}
#[tauri::command]
pub fn native_microphone_supported() -> bool {
    cfg!(windows)
}
#[tauri::command]
pub fn report_audio_diagnostic(window: WebviewWindow, stage: String, detail: String) -> Result<(), String> {
    authorize(&window)?;
    if !["capture-ready", "capture-error", "playback-error", "playback-ready", "realtime"].contains(&stage.as_str()) {
        return Err("Unknown audio diagnostic".into());
    }
    let detail: String = detail.chars().filter(|c| !c.is_control()).take(512).collect();
    log::info!("[AudioPipeline] {stage}: {detail}");
    Ok(())
}
#[tauri::command]
pub async fn native_microphone_devices(window: WebviewWindow) -> Result<Vec<InputDevice>, String> {
    authorize(&window)?;
    #[cfg(windows)]
    return tokio::task::spawn_blocking(platform::devices)
        .await
        .map_err(|e| e.to_string())?;
    #[cfg(not(windows))]
    Err("Native microphone requires Windows".into())
}
#[tauri::command]
pub async fn native_microphone_start(
    window: WebviewWindow,
    device_id: String,
    system_processing: bool,
) -> Result<Capture, String> {
    authorize(&window)?;
    #[cfg(windows)]
    {
        let id = uuid::Uuid::new_v4().to_string();
        let stop = Arc::new(AtomicBool::new(false));
        let (requests, receive) = std::sync::mpsc::sync_channel(1);
        let (ready, initialized) = tokio::sync::oneshot::channel();
        {
            let mut all = sessions().lock().unwrap_or_else(|e| e.into_inner());
            if all.len() >= 4 {
                return Err("同时使用麦克风的任务过多，请停止试音后重试".into());
            }
            all.insert(
                id.clone(),
                Session {
                    stop: stop.clone(),
                    requests,
                },
            );
        }
        let worker_id = id.clone();
        let spawn = std::thread::Builder::new()
            .name("native-microphone".into())
            .spawn(move || {
                let result = platform::run(device_id, system_processing, stop, receive, ready);
                if let Err(e) = result {
                    log::warn!("原生麦克风采集结束: {e}");
                }
                sessions()
                    .lock()
                    .unwrap_or_else(|e| e.into_inner())
                    .remove(&worker_id);
            });
        if let Err(e) = spawn {
            stop_id(&id);
            return Err(e.to_string());
        }
        match tokio::time::timeout(Duration::from_secs(10), initialized).await {
            Ok(Ok(Ok(device_id))) => Ok(Capture {
                id,
                device_id,
                sample_rate: 48000,
            }),
            other => {
                stop_id(&id);
                Err(match other {
                    Ok(Ok(Err(e))) => e,
                    _ => "麦克风启动超时，请检查设备和 Windows 麦克风隐私设置".into(),
                })
            }
        }
    }
    #[cfg(not(windows))]
    {
        let _ = (device_id, system_processing);
        Err("Native microphone requires Windows".into())
    }
}
#[tauri::command]
pub async fn native_microphone_read(
    window: WebviewWindow,
    id: String,
) -> Result<tauri::ipc::Response, String> {
    authorize(&window)?;
    let requests = sessions()
        .lock()
        .unwrap_or_else(|e| e.into_inner())
        .get(&id)
        .map(|s| s.requests.clone())
        .ok_or("麦克风采集已停止")?;
    let (tx, rx) = tokio::sync::oneshot::channel();
    requests
        .try_send(tx)
        .map_err(|_| "麦克风读取繁忙或已停止")?;
    let bytes = tokio::time::timeout(Duration::from_secs(3), rx)
        .await
        .map_err(|_| "麦克风无响应，请检查设备")?
        .map_err(|_| "麦克风设备已断开或采集已停止")??;
    Ok(tauri::ipc::Response::new(bytes))
}
#[tauri::command]
pub fn native_microphone_stop(window: WebviewWindow, id: String) -> Result<(), String> {
    authorize(&window)?;
    stop_id(&id);
    Ok(())
}
fn stop_id(id: &str) {
    if let Some(s) = sessions()
        .lock()
        .unwrap_or_else(|e| e.into_inner())
        .remove(id)
    {
        s.stop.store(true, Ordering::Release);
    }
}
pub fn stop_all() {
    for (_, s) in sessions().lock().unwrap_or_else(|e| e.into_inner()).drain() {
        s.stop.store(true, Ordering::Release);
    }
}

#[cfg(windows)]
#[path = "native_microphone/windows.rs"]
mod platform;
