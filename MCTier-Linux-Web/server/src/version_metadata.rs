//! Fixed upstream metadata endpoint, not a general URL proxy.
use futures_util::StreamExt;
use std::{sync::OnceLock, time::{Duration, Instant}};
use tokio::sync::Mutex;
const URL: &str = "https://gitee.com/api/v5/repos/peng-minghang/mctier/tags?per_page=100&page=1";
const LIMIT: usize = 256 * 1024;
type Cache = Mutex<Option<(Instant, String)>>;
static CACHE: OnceLock<Cache> = OnceLock::new();

fn validate(bytes: &[u8]) -> Result<String, String> {
    if bytes.len() > LIMIT { return Err("版本响应过大".into()); }
    let value: serde_json::Value = serde_json::from_slice(bytes).map_err(|_| "版本响应不是 JSON")?;
    if !value.as_array().is_some_and(|tags| tags.len() <= 100) { return Err("版本标签清单无效".into()); }
    String::from_utf8(bytes.to_vec()).map_err(|_| "版本响应编码无效".into())
}
pub async fn fetch_tags() -> Result<String, String> {
    let mut cache = CACHE.get_or_init(|| Mutex::new(None)).lock().await;
    if let Some((at, text)) = cache.as_ref() {
        if at.elapsed() < Duration::from_secs(300) { return Ok(text.clone()); }
    }
    let fetch = async {
        let response = reqwest::Client::builder().redirect(reqwest::redirect::Policy::none())
            .timeout(Duration::from_secs(10)).connect_timeout(Duration::from_secs(5))
            .build().map_err(|_| "无法初始化版本查询")?
            .get(URL).header("Accept", "application/json").send().await
            .map_err(|_| "无法连接原版 Gitee 版本接口")?;
        if !response.status().is_success() { return Err(format!("版本接口返回 HTTP {}", response.status().as_u16())); }
        if response.content_length().is_some_and(|n| n > LIMIT as u64) { return Err("版本响应过大".into()); }
        if response.headers().get(reqwest::header::CONTENT_TYPE).is_some_and(|v| !v.to_str().unwrap_or("").to_ascii_lowercase().starts_with("application/json")) {
            return Err("版本接口返回非 JSON 内容".into());
        }
        let mut stream = response.bytes_stream();
        let mut bytes = Vec::new();
        while let Some(chunk) = stream.next().await {
            let chunk = chunk.map_err(|_| "版本响应读取失败")?;
            if bytes.len() + chunk.len() > LIMIT { return Err("版本响应过大".into()); }
            bytes.extend_from_slice(&chunk);
        }
        validate(&bytes)
    };
    let text = tokio::time::timeout(Duration::from_secs(10), fetch).await.map_err(|_| "版本查询超时")??;
    *cache = Some((Instant::now(), text.clone()));
    Ok(text)
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn metadata_is_bounded_and_requires_a_tag_array() {
        assert!(validate(b"[]").is_ok());
        assert!(validate(b"{}").is_err());
        assert!(validate(&vec![b' '; LIMIT + 1]).is_err());
        assert!(validate(serde_json::to_string(&vec![0; 101]).unwrap().as_bytes()).is_err());
    }
}
