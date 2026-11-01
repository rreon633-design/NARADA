//! Separately negotiated inbound-only desktop extension. Existing health operation lists stay exact.
//! The sender must first commit an Olm message containing the attachment secret; no key, filename,
//! plaintext hash or mutable descriptor is accepted by these endpoints.
use super::*;
use serde::Deserialize;
use serde_json::{json, Value};
#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct UploadWire { hello: Hello, version: u16, action: UploadAction }
#[derive(Deserialize)]
#[serde(tag="kind",rename_all="snake_case",deny_unknown_fields)]
enum UploadAction {
    Capabilities {},
    Begin { message_id:String, blob_id:String },
    Status { message_id:String, blob_id:String },
    Finish { message_id:String, blob_id:String },
}
impl Service {
    pub(super) async fn route_attachment_upload(&self, request:HttpRequest<Incoming>, fp:&str, context:&SessionContext)->Result<Value,RpcError>{
        let message:UploadWire=transport::body(request).await?;message.hello.validate()?;
        if message.version!=1{return Err(err("PROTOCOL_INCOMPATIBLE","Unsupported attachment upload version."));}
        let _guard=self.0.files.gate().read_owned().await;
        if context.cancel.is_cancelled(){return Err(err("SESSION_INVALIDATED","This attachment session was invalidated."));}
        let peer=self.file_peer(fp,context).await?;
        self.0.store.authorize(&peer.id,peer.revision,peer.epoch,Capability::Chat,None).await?;
        self.0.store.authorize(&peer.id,peer.revision,peer.epoch,Capability::Attachment,None).await?;
        let content=self.content()?;
        match message.action{
            UploadAction::Capabilities{}=>Ok(json!({"version":1,"operation":"attachments.upload.v1","crypto":"age.x25519.v1","chunk_size":262144,"max_plaintext":8388608,"max_ciphertext":8454144,"max_chunks":33})),
            UploadAction::Begin{message_id,blob_id}=>content.begin_upload(&peer.id,&message_id,&blob_id).await,
            UploadAction::Status{message_id,blob_id}=>content.upload_status(&peer.id,&message_id,&blob_id).await,
            UploadAction::Finish{message_id,blob_id}=>content.finish_upload(&peer.id,&message_id,&blob_id).await,
        }
    }
    pub(crate) async fn route_attachment_upload_chunk(&self,request:HttpRequest<Incoming>,bootstrap:bool,context:&SessionContext)->Result<Value,RpcError>{
        {
            let mut budget=context.budget.lock().map_err(|_|err("RATE_LIMITED","The session budget is unavailable."))?;
            if budget.0.elapsed()>Duration::from_secs(1){*budget=(Instant::now(),0);}
            if budget.1>=12{return Err(err("RATE_LIMITED","The per-session request limit was reached."));}
            budget.1+=1;
        }
        if bootstrap||context.cancel.is_cancelled()||request.method()!=hyper::Method::PUT||request.uri().query().is_some()||request.uri().scheme().is_some(){
            return Err(err("NO_PEER_ROUTE","Only an activated peer attachment upload is accepted."));
        }
        if request.headers().get_all("x-narada-upload-version").iter().count()!=1||request.headers().get("x-narada-upload-version").and_then(|v|v.to_str().ok())!=Some("1"){
            return Err(err("PROTOCOL_INCOMPATIBLE","Explicit attachment upload version 1 is required."));
        }
        // Reject ambiguous duplicate framing/checksum fields before the shared bounded body reader.
        for name in ["content-length","content-type","x-narada-transfer-version","x-narada-sha256"]{
            if request.headers().get_all(name).iter().count()!=1{return Err(err("INVALID_CHUNK","Exact single-valued framing is required."));}
        }
        let path=request.uri().path().strip_prefix("/api/v1/attachment-upload/chunks/").ok_or_else(||err("NO_PEER_ROUTE","Unknown attachment route."))?;
        let parts:Vec<_>=path.split('/').collect();
        if parts.len()!=3{return Err(err("INVALID_CHUNK","The attachment chunk route is invalid."));}
        file_id(parts[0])?;file_id(parts[1])?;
        let index=parts[2].parse::<u32>().map_err(|_|err("INVALID_CHUNK","The chunk index is invalid."))?;
        if index>=33||index.to_string()!=parts[2]{return Err(err("INVALID_CHUNK","The chunk index is not canonical."));}
        let message=parts[0].to_owned();let blob=parts[1].to_owned();
        let fp=context.fingerprint.as_deref().ok_or_else(||err("CERTIFICATE_REQUIRED","Mutual TLS is required."))?;
        let _guard=self.0.files.gate().read_owned().await;
        if context.cancel.is_cancelled(){return Err(err("SESSION_INVALIDATED","This attachment session was invalidated."));}
        let peer=self.file_peer(fp,context).await?;
        self.0.store.authorize(&peer.id,peer.revision,peer.epoch,Capability::Chat,None).await?;
        self.0.store.authorize(&peer.id,peer.revision,peer.epoch,Capability::Attachment,None).await?;
        let bytes=transport::binary_body(request,true).await?;
        if context.cancel.is_cancelled(){return Err(err("SESSION_INVALIDATED","This attachment session was invalidated."));}
        self.content()?.upload_chunk(&peer.id,&message,&blob,index,bytes).await?;
        Ok(json!({"version":1,"message_id":message,"blob_id":blob,"index":index,"stored":true}))
    }
}
