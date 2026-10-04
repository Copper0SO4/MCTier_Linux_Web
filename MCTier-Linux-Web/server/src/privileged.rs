//! Authenticate first, revalidate in the caller, then submit a single bounded request.
use std::{io::Write, process::Stdio, time::Duration};
use tokio::{
    io::{AsyncReadExt, AsyncWriteExt, BufReader},
    process::{Child, ChildStdin, ChildStdout, Command},
    sync::watch,
};
const READY: &[u8] = b"MCTierWeb helper ready\n";
pub const HOSTS_SWITCH: &str = "--mctier-write-hosts";
pub fn ready() {
    let mut out = std::io::stdout();
    let _ = out.write_all(READY);
    let _ = out.flush();
}
pub fn hosts_ready() {
    let args: Vec<_> = std::env::args_os().collect();
    if args.len() == 2 && args[1] == HOSTS_SWITCH && unsafe { libc::geteuid() } == 0 {
        ready();
    }
}
pub struct Helper {
    child: Child,
    input: ChildStdin,
    output: BufReader<ChildStdout>,
}
impl Helper {
    pub async fn start(switch: &str, cancel: &mut watch::Receiver<u64>) -> Result<Self, String> {
        let mut command = Command::new("/usr/bin/pkexec");
        let executable = std::env::current_exe().map_err(|e| e.to_string())?;
        if !executable.is_file() {
            return Err("服务可执行文件已更新，请先重启服务再申请系统授权".into());
        }
        command.arg(executable).arg(switch);
        Self::spawn(command, cancel, Duration::from_secs(180)).await
    }
    async fn spawn(
        mut command: Command,
        cancel: &mut watch::Receiver<u64>,
        timeout: Duration,
    ) -> Result<Self, String> {
        if cancel.has_changed().unwrap_or(true) {
            return Err("操作已取消，请重新预览".into());
        }
        let mut child = command
            .stdin(Stdio::piped())
            .stdout(Stdio::piped())
            .stderr(Stdio::null())
            .kill_on_drop(true)
            .spawn()
            .map_err(|_| "无法启动授权助手，请检查 polkit 和桌面认证代理")?;
        let input = child.stdin.take().ok_or("授权输入管道不可用")?;
        let mut output = BufReader::new(child.stdout.take().ok_or("授权输出管道不可用")?);
        let mut marker = vec![0; READY.len()];
        tokio::select! {
            _ = cancel.changed() => return Err("操作已取消；认证等待期间未提交系统改动".into()),
            result = tokio::time::timeout(timeout, output.read_exact(&mut marker)) => {
                result.map_err(|_| "认证等待超时，未提交系统改动")?.map_err(|_| "授权已取消、认证失败或助手未就绪")?;
            }
        }
        if marker != READY {
            return Err("授权助手握手无效，未提交系统改动".into());
        }
        Ok(Self {
            child,
            input,
            output,
        })
    }
    pub async fn execute(
        mut self,
        request: serde_json::Value,
        cancel: &mut watch::Receiver<u64>,
    ) -> Result<Vec<u8>, String> {
        if cancel.has_changed().unwrap_or(true) {
            return Err("操作已取消，未提交系统改动".into());
        }
        let data = serde_json::to_vec(&request).map_err(|e| e.to_string())?;
        if data.len() > crate::modules::hosts_security::MAX_HOSTS_BYTES * 2 + 4096 {
            return Err("授权请求过大".into());
        }
        let run = async {
            self.input
                .write_all(&data)
                .await
                .map_err(|_| "授权请求提交失败")?;
            drop(self.input);
            let mut result = Vec::new();
            (&mut self.output)
                .take(16385)
                .read_to_end(&mut result)
                .await
                .map_err(|_| "授权结果读取失败")?;
            if result.len() > 16384 {
                return Err("授权结果超出限制".to_string());
            }
            let status = self.child.wait().await.map_err(|_| "授权助手退出异常")?;
            if !status.success() && result.is_empty() {
                return Err("授权助手拒绝操作，请重新预览".into());
            }
            Ok(result)
        };
        tokio::select! {
            _ = cancel.changed() => Err("操作已取消；请求可能已提交，请检查系统状态及撤销入口".into()),
            result = tokio::time::timeout(Duration::from_secs(30), run) => result.map_err(|_| "操作结果等待超时，可能部分应用，请检查系统状态及撤销入口")?,
        }
    }
}
#[cfg(test)]
mod tests {
    use super::*;
    #[tokio::test]
    async fn mock_helper_accepts_only_after_ready() {
        let (_sender, mut cancel) = watch::channel(0);
        let mut command = Command::new("/bin/sh");
        command.args(["-c", "printf 'MCTierWeb helper ready\\n'; cat"]);
        let helper = Helper::spawn(command, &mut cancel, Duration::from_secs(2))
            .await
            .unwrap();
        assert_eq!(
            helper
                .execute(serde_json::json!({"mock":true}), &mut cancel)
                .await
                .unwrap(),
            b"{\"mock\":true}"
        );
    }
    #[tokio::test]
    async fn cancel_after_authentication_never_submits() {
        let (sender, mut cancel) = watch::channel(0);
        let mut command = Command::new("/bin/sh");
        command.args(["-c", "printf 'MCTierWeb helper ready\\n'; cat"]);
        let helper = Helper::spawn(command, &mut cancel, Duration::from_secs(2))
            .await
            .unwrap();
        sender.send_modify(|n| *n += 1);
        assert!(helper
            .execute(serde_json::json!({"mock":true}), &mut cancel)
            .await
            .unwrap_err()
            .contains("未提交"));
    }
    #[tokio::test]
    async fn cancellation_interrupts_authentication_wait() {
        let (sender, mut cancel) = watch::channel(0);
        let mut command = Command::new("/bin/sh");
        command.args(["-c", "read ignored"]);
        let abort = tokio::spawn(async move {
            tokio::time::sleep(Duration::from_millis(10)).await;
            sender.send_modify(|n| *n += 1);
        });
        assert!(Helper::spawn(command, &mut cancel, Duration::from_secs(2))
            .await
            .err()
            .unwrap()
            .contains("未提交"));
        abort.await.unwrap();
    }
    #[tokio::test]
    async fn reject_unknown_handshake() {
        let (_sender, mut cancel) = watch::channel(0);
        let mut command = Command::new("/bin/sh");
        command.args(["-c", "printf 'invalid helper output\\n'"]);
        assert!(Helper::spawn(command, &mut cancel, Duration::from_secs(2))
            .await
            .is_err());
    }
}
