use crate::{
    policy::{self, err},
    safe_fs::{self, Info, SafeRoot},
};
use base64::{engine::general_purpose::STANDARD as B64, Engine};
use narada_protocol::RpcError;
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use std::{
    collections::HashMap,
    io::{Cursor, Read},
    path::Path,
    time::{Duration, Instant},
};
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Entry {
    pub object_id: String,
    pub root_id: String,
    pub relative_path: String,
    pub parent_path: String,
    pub name: String,
    pub info: Info,
}
pub struct Scan {
    pub entries: Vec<Entry>,
    pub omitted: usize,
    pub signature: String,
}
pub fn scan(
    path: &Path,
    identity: &str,
    root_id: &str,
    exclusions: &[String],
    old: HashMap<String, (String, String)>,
) -> Result<Scan, RpcError> {
    let root = SafeRoot::open(path, Some(identity))?;
    let until = Instant::now() + Duration::from_secs(3);
    let mut queue = vec![String::new()];
    let mut entries = Vec::new();
    let mut omitted: usize = 0;
    let mut visited = 0;
    while let Some(parent) = queue.pop() {
        let dir = root.open_relative(&parent, false)?;
        let mut names = root.names(&dir)?;
        names.sort_by_key(|s| policy::fold(s));
        let mut counts = HashMap::new();
        for n in &names {
            *counts.entry(policy::fold(n)).or_insert(0usize) += 1;
        }
        for name in names {
            visited += 1;
            if visited > 5000 || Instant::now() > until {
                return Err(err("INDEX_LIMIT","The root exceeds 5,000 visited entries or the three-second scan budget. Choose a smaller folder or exclude subtrees."));
            }
            let rel = if parent.is_empty() {
                name.clone()
            } else {
                format!("{parent}/{name}")
            };
            if policy::excluded(&rel, exclusions)
                || counts.get(&policy::fold(&name)).is_some_and(|n| *n > 1)
            {
                omitted += 1;
                continue;
            }
            let info = match root.info(&rel) {
                Ok(v) if !v.hidden && v.size <= i64::MAX as u64 => v,
                _ => {
                    omitted += 1;
                    continue;
                }
            };
            let object_id = old
                .get(&rel)
                .filter(|(_, id)| id == &info.identity)
                .map(|(id, _)| id.clone())
                .unwrap_or_else(|| uuid::Uuid::new_v4().to_string());
            if info.kind == "directory" {
                queue.push(rel.clone());
            }
            entries.push(Entry {
                object_id,
                root_id: root_id.into(),
                relative_path: rel,
                parent_path: parent.clone(),
                name,
                info,
            });
        }
    }
    root.verify()?;
    entries.sort_by(|a, b| a.relative_path.cmp(&b.relative_path));
    let mut hasher = Sha256::new();
    for e in &entries {
        hasher.update(e.relative_path.as_bytes());
        hasher.update([0]);
        hasher.update(e.info.version.as_bytes());
        hasher.update([0]);
    }
    hasher.update(omitted.to_le_bytes());
    Ok(Scan {
        entries,
        omitted,
        signature: hex::encode(hasher.finalize()),
    })
}
pub fn validate_live(
    path: &Path,
    identity: &str,
    exclusions: &[String],
    entry: &Entry,
) -> Result<Info, RpcError> {
    if policy::excluded(&entry.relative_path, exclusions) {
        return Err(err(
            "ACCESS_DENIED",
            "This entry is excluded by the current root policy.",
        ));
    }
    let root = SafeRoot::open(path, Some(identity))?;
    let actual = root.info(&entry.relative_path)?;
    if actual.hidden
        || actual.identity != entry.info.identity
        || actual.version != entry.info.version
    {
        return Err(err(
            "STALE_VERSION",
            "The indexed object changed. Refresh the directory before using it.",
        ));
    }
    root.verify()?;
    Ok(actual)
}
pub fn preview(
    path: &Path,
    identity: &str,
    exclusions: &[String],
    entry: &Entry,
    expected: &str,
) -> Result<serde_json::Value, RpcError> {
    if policy::excluded(&entry.relative_path, exclusions) || entry.info.kind != "file" {
        return Err(err(
            "PREVIEW_UNSUPPORTED",
            "Only permitted regular PNG/JPEG images have previews.",
        ));
    }
    let root = SafeRoot::open(path, Some(identity))?;
    let mut object = root.open_relative(&entry.relative_path, false)?;
    let before = safe_fs::info(&object.file)?;
    if before.hidden
        || before.identity != entry.info.identity
        || before.version != expected
        || before.version != entry.info.version
    {
        return Err(err(
            "STALE_VERSION",
            "The image changed. Refresh its metadata first.",
        ));
    }
    if before.size > 5 * 1024 * 1024 {
        return Err(err(
            "PREVIEW_LIMIT",
            "Image previews accept at most 5 MiB of source bytes.",
        ));
    }
    let mut bytes = Vec::new();
    object
        .file
        .by_ref()
        .take(5 * 1024 * 1024 + 1)
        .read_to_end(&mut bytes)
        .map_err(safe_fs::io_error)?;
    if bytes.len() > 5 * 1024 * 1024 {
        return Err(err(
            "PREVIEW_LIMIT",
            "The source grew beyond the preview limit.",
        ));
    }
    let format=image::guess_format(&bytes).map_err(|_|err("PREVIEW_UNSUPPORTED","Only PNG and JPEG images are supported; SVG, HTML, PDF and other active formats are not opened."))?;
    if !matches!(format, image::ImageFormat::Png | image::ImageFormat::Jpeg) {
        return Err(err(
            "PREVIEW_UNSUPPORTED",
            "Only PNG/JPEG pixel previews are supported.",
        ));
    }
    let mut limits = image::Limits::default();
    limits.max_image_width = Some(4096);
    limits.max_image_height = Some(4096);
    limits.max_alloc = Some(96 * 1024 * 1024);
    let mut reader = image::ImageReader::with_format(Cursor::new(&bytes), format);
    reader.limits(limits);
    let decoded = reader.decode().map_err(|_| {
        err(
            "PREVIEW_LIMIT",
            "The image is malformed or exceeds the 4096-pixel/decoder-allocation limits.",
        )
    })?;
    // Re-encode pixels; no original EXIF, metadata, active document or file bytes leave this route.
    let thumb = decoded
        .thumbnail(decoded.width().min(256), decoded.height().min(256))
        .to_rgba8();
    let mut rgb = image::RgbImage::new(thumb.width(), thumb.height());
    for (x, y, p) in thumb.enumerate_pixels() {
        let a = p[3] as u16;
        rgb.put_pixel(
            x,
            y,
            image::Rgb([0, 1, 2].map(|n| ((p[n] as u16 * a + 255 * (255 - a)) / 255) as u8)),
        );
    }
    let mut output = Vec::new();
    image::codecs::jpeg::JpegEncoder::new_with_quality(&mut output, 75)
        .encode_image(&rgb)
        .map_err(|_| {
            err(
                "PREVIEW_UNSUPPORTED",
                "The bounded preview could not be encoded.",
            )
        })?;
    if output.len() > 32 * 1024 {
        return Err(err(
            "PREVIEW_LIMIT",
            "The generated preview exceeds its 32 KiB response budget.",
        ));
    }
    let after = safe_fs::info(&object.file)?;
    if after.version != before.version || after.identity != before.identity {
        return Err(err(
            "STALE_VERSION",
            "The source changed during decoding. No preview was returned.",
        ));
    }
    let current = root.info(&entry.relative_path)?;
    if current.identity != before.identity || current.version != before.version {
        return Err(err(
            "STALE_VERSION",
            "The image moved or changed during preview. Refresh before retrying.",
        ));
    }
    root.verify()?;
    Ok(
        serde_json::json!({"object_id":entry.object_id,"version":before.version,"mime":"image/jpeg","width":rgb.width(),"height":rgb.height(),"data":B64.encode(output),"notice":"Re-encoded pixel preview only. Original metadata is stripped; this is not a file download."}),
    )
}
pub fn new_name(name: &str) -> Result<(), RpcError> {
    policy::component(name)?;
    if policy::excluded(name, &[]) {
        Err(err(
            "EXCLUDED_NAME",
            "Hidden and reserved names cannot be created through file operations.",
        ))
    } else {
        Ok(())
    }
}
pub fn entry_view(e: &Entry, parent_id: Option<&str>) -> serde_json::Value {
    serde_json::json!({"object_id":e.object_id,"name":e.name,"kind":e.info.kind,"version":e.info.version,"size":e.info.size.to_string(),"modified":e.info.modified,"relative_path":e.relative_path,"parent_id":parent_id,"preview_candidate":e.info.kind=="file"&&["png","jpg","jpeg"].iter().any(|s|e.name.to_lowercase().ends_with(&format!(".{s}")))})
}
pub fn signature_query<T: Serialize>(v: &T) -> String {
    hex::encode(Sha256::digest(serde_json::to_vec(v).unwrap_or_default()))
}
// Deliberately no global content cache; preview buffers are request-bounded.
