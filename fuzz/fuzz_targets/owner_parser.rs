#![no_main]
use libfuzzer_sys::fuzz_target;
fuzz_target!(|data: &[u8]| {
    if data.len() > narada_protocol::MAX_FRAME_BYTES { return; }
    let _ = serde_json::from_slice::<narada_protocol::Request>(data);
    let _ = serde_json::from_slice::<narada_protocol::SyncRequest>(data);
    if let Ok(manifest) = serde_json::from_slice::<narada_protocol::TransferManifest>(data) { let _ = manifest.validate(); }
    if let Ok(text) = std::str::from_utf8(data) { let _ = narada_protocol::chat_text(text, true); let _ = narada_protocol::file_id(text); }
});
