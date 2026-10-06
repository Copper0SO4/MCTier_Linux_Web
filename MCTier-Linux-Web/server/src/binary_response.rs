//! Keep concurrency permits alive until the browser consumes or drops the body.
use axum::{body::Body, http::header, response::{IntoResponse, Response}};
use bytes::Bytes;
use std::convert::Infallible;

pub fn download<P: Send + 'static>(bytes: Vec<u8>, permit: P) -> Response {
    let stream = async_stream::stream! {
        let _permit = permit;
        let bytes = Bytes::from(bytes);
        for offset in (0..bytes.len()).step_by(64 * 1024) {
            yield Ok::<_, Infallible>(bytes.slice(offset..(offset + 64 * 1024).min(bytes.len())));
        }
    };
    (
        [(header::CONTENT_TYPE, "application/octet-stream"), (header::CONTENT_DISPOSITION, "attachment")],
        Body::from_stream(stream),
    ).into_response()
}

#[cfg(test)]
mod tests {
    use super::*;
    use futures_util::StreamExt;
    #[tokio::test]
    async fn permits_survive_response_creation_and_release_on_drop() {
        let gate = std::sync::Arc::new(tokio::sync::Semaphore::new(1));
        let permit = gate.clone().try_acquire_owned().unwrap();
        let response = download(vec![0; 130_000], permit);
        assert!(gate.try_acquire().is_err());
        let mut stream = response.into_body().into_data_stream();
        assert_eq!(stream.next().await.unwrap().unwrap().len(), 65536);
        assert!(gate.try_acquire().is_err());
        drop(stream);
        assert!(gate.try_acquire().is_ok());
    }
}
