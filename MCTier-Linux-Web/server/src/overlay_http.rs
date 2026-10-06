//! Transport policy for authenticated EasyTier HTTP endpoints only.
//! Never send overlay headers to a system proxy or follow a peer redirect.
use std::time::Duration;
pub fn client(timeout: Duration, connect_timeout: Duration) -> reqwest::ClientBuilder {
    reqwest::Client::builder()
        .no_proxy()
        .redirect(reqwest::redirect::Policy::none())
        .timeout(timeout)
        .connect_timeout(connect_timeout)
}

#[cfg(test)]
mod tests {
    use super::*;
    use tokio::io::{AsyncReadExt, AsyncWriteExt};
    #[tokio::test]
    async fn environment_proxy_is_bypassed_without_forwarding_overlay_headers() {
        const TARGET: &str = "MCTIER_TEST_OVERLAY_TARGET";
        if let Ok(target) = std::env::var(TARGET) {
            // This subprocess alone receives proxy env vars; parallel tests are unaffected.
            let baseline = reqwest::Client::new().get(&target).send().await.unwrap();
            assert_eq!(baseline.status(), reqwest::StatusCode::BAD_GATEWAY);
            let direct = client(Duration::from_secs(2), Duration::from_secs(1)).build().unwrap()
                .get(target).header("x-mctier-chat-token", "fixture-only").send().await.unwrap();
            assert_eq!(direct.status(), reqwest::StatusCode::OK);
            return;
        }
        let proxy = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let target = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let proxy_address = format!("http://{}", proxy.local_addr().unwrap());
        let target_address = format!("http://{}/api/test", target.local_addr().unwrap());
        let proxy_task = tokio::spawn(async move {
            let (mut stream, _) = proxy.accept().await.unwrap();
            let mut bytes = vec![0; 4096];
            let n = stream.read(&mut bytes).await.unwrap();
            assert!(!String::from_utf8_lossy(&bytes[..n]).contains("fixture-only"));
            stream.write_all(b"HTTP/1.1 502 Bad Gateway\r\nContent-Length: 0\r\nConnection: close\r\n\r\n").await.unwrap();
            // If policy regresses, the direct request will reach this listener too.
            assert!(tokio::time::timeout(Duration::from_millis(500), proxy.accept()).await.is_err());
        });
        let target_task = tokio::spawn(async move {
            let (mut stream, _) = target.accept().await.unwrap();
            let mut bytes = vec![0; 4096];
            let n = stream.read(&mut bytes).await.unwrap();
            assert!(String::from_utf8_lossy(&bytes[..n]).starts_with("GET /api/test "));
            stream.write_all(b"HTTP/1.1 200 OK\r\nContent-Length: 0\r\nConnection: close\r\n\r\n").await.unwrap();
        });
        let mut command = tokio::process::Command::new(std::env::current_exe().unwrap());
        command.args(["--exact", "overlay_http::tests::environment_proxy_is_bypassed_without_forwarding_overlay_headers"])
            .env(TARGET, target_address).env("HTTP_PROXY", &proxy_address).env("http_proxy", &proxy_address)
            .env("ALL_PROXY", &proxy_address).env("all_proxy", &proxy_address)
            .env_remove("NO_PROXY").env_remove("no_proxy").kill_on_drop(true);
        let output = tokio::time::timeout(Duration::from_secs(10), command.output()).await.unwrap().unwrap();
        assert!(output.status.success(), "{}", String::from_utf8_lossy(&output.stdout));
        proxy_task.await.unwrap(); target_task.await.unwrap();
    }
}
