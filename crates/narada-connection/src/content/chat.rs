use super::*;
use vodozemac::{Curve25519PublicKey, Ed25519PublicKey, Ed25519Signature};
use zeroize::Zeroizing;
fn bundle_statement(b: &Bundle) -> Result<Vec<u8>, RpcError> {
    serde_json::to_vec(&(
        "NARADA-CHAT-BUNDLE-1",
        b.version,
        &b.transport_id,
        &b.recipient,
        &b.curve,
        &b.ed,
        &b.one_time,
    ))
    .map_err(|_| RpcError::storage())
}
fn ensure_peer(d: &mut Document, peer: &str) -> Result<(), RpcError> {
    chat_peer(peer)?;
    if d.peers.contains_key(peer) {
        return Ok(());
    }
    if d.peers.len() >= 32 {
        return Err(err(
            "CONTENT_QUOTA",
            "At most 32 protected chat peer bindings are retained.",
        ));
    }
    let mut a = account(d)?;
    a.generate_one_time_keys(1);
    let one_time = a
        .one_time_keys()
        .values()
        .next()
        .ok_or_else(|| err("CRYPTO_UNAVAILABLE", "A one-time key could not be created."))?
        .to_base64();
    let mut b = Bundle {
        version: 1,
        transport_id: d.device.clone(),
        recipient: peer.into(),
        curve: a.curve25519_key().to_base64(),
        ed: a.ed25519_key().to_base64(),
        one_time,
        signature: String::new(),
    };
    b.signature = a.sign(bundle_statement(&b)?).to_base64();
    a.mark_keys_as_published();
    d.account = encode(&a.pickle())?;
    d.peers.insert(
        peer.into(),
        ChatPeer {
            ours: b,
            remote: None,
            sessions: Vec::new(),
        },
    );
    Ok(())
}
fn bind(d: &mut Document, peer: &str, b: &Bundle) -> Result<(), RpcError> {
    if b.version != 1
        || b.transport_id != peer
        || b.recipient != d.device
        || b.curve.len() > 44
        || b.ed.len() > 44
        || b.one_time.len() > 44
        || b.signature.len() > 88
    {
        return Err(err(
            "CHAT_KEY_INVALID",
            "The chat bundle is not bound to these paired endpoints.",
        ));
    }
    let key = Ed25519PublicKey::from_base64(&b.ed)
        .map_err(|_| err("CHAT_KEY_INVALID", "The chat signing key is invalid."))?;
    let sig = Ed25519Signature::from_base64(&b.signature)
        .map_err(|_| err("CHAT_KEY_INVALID", "The signed pre-key is invalid."))?;
    key.verify(&bundle_statement(b)?, &sig)
        .map_err(|_| err("CHAT_KEY_INVALID", "The signed pre-key did not verify."))?;
    Curve25519PublicKey::from_base64(&b.curve)
        .map_err(|_| err("CHAT_KEY_INVALID", "Invalid chat identity key."))?;
    Curve25519PublicKey::from_base64(&b.one_time)
        .map_err(|_| err("CHAT_KEY_INVALID", "Invalid one-time key."))?;
    ensure_peer(d, peer)?;
    let p = d.peers.get_mut(peer).ok_or_else(vault::locked)?;
    if p.remote
        .as_ref()
        .is_some_and(|old| old.curve != b.curve || old.ed != b.ed)
    {
        return Err(err("CHAT_KEY_CHANGED","This paired endpoint supplied a different E2EE identity. Sending is blocked; no session or identity was silently reset."));
    }
    p.remote = Some(b.clone());
    Ok(())
}
impl Content {
    pub async fn bundle(&self, peer: &str) -> Result<Bundle, RpcError> {
        if let Some(b) = self
            .0
            .vault
            .read(|d| Ok(d.peers.get(peer).map(|p| p.ours.clone())))
            .await?
        {
            return Ok(b);
        }
        self.0
            .vault
            .update(|d| {
                ensure_peer(d, peer)?;
                Ok(d.peers.get(peer).ok_or_else(vault::locked)?.ours.clone())
            })
            .await
    }
    pub async fn existing_send(&self, input: &SendChat) -> Result<Option<Value>, RpcError> {
        let hash = digest(input)?;
        self.0
            .vault
            .read(|d| {
                if let Some(m) = d.messages.iter().find(|m| m.id == input.message_id) {
                    if m.direction != "outgoing"
                        || m.peer != input.peer_id
                        || m.request_hash != hash
                    {
                        return Err(err(
                            "MESSAGE_ID_CONFLICT",
                            "The logical message ID already names different content.",
                        ));
                    }
                    return Ok(Some(message_view(d, m)));
                }
                if d.messages.len() >= MAX_CONTENT_MESSAGES {
                    return Err(err(
                        "CONTENT_QUOTA",
                        "Protected history/outbox is full. No messages were silently evicted.",
                    ));
                }
                Ok(None)
            })
            .await
    }
    pub async fn queue(
        &self,
        input: SendChat,
        files: &narada_files::Files,
        policy: &tokio::sync::OwnedRwLockReadGuard<()>,
    ) -> Result<Value, RpcError> {
        input.validate()?;
        if let Some(old) = self.existing_send(&input).await? {
            return Ok(old);
        }
        let hash = digest(&input)?;
        let prepared = if let Some(source) = &input.attachment {
            Some(
                self.prepare_attachment(
                    &uuid::Uuid::new_v4().to_string(),
                    &input.peer_id,
                    source,
                    files,
                    policy,
                )
                .await?,
            )
        } else {
            None
        };
        self.0.vault.update(|d|{if d.messages.len()>=MAX_CONTENT_MESSAGES{return Err(err("CONTENT_QUOTA","The protected message limit is full."));}
if d.messages.iter().any(|m|m.id==input.message_id){return Err(err("MESSAGE_ID_CONFLICT","This message was already queued by another owner request."));}
let body=MessageBody{domain:"NARADA-CHAT-1".into(),message_id:input.message_id.clone(),sender:d.device.clone(),recipient:input.peer_id.clone(),text:input.text,attachment:prepared.as_ref().map(|(s,_)|s.clone())};if serde_json::to_vec(&body).map_err(|_|RpcError::storage())?.len()>8500{return Err(err("MESSAGE_TOO_LARGE","The combined text and encrypted attachment descriptor exceed the message bound."));}
if let Some((_,blob))=prepared{d.blobs.insert(blob.id.clone(),blob);}d.drafts.remove(&input.peer_id);
let m=Message{id:input.message_id,peer:input.peer_id,direction:"outgoing".into(),state:"pending".into(),request_hash:hash,body,envelope:None,digest:None,receipt:None,created:now(),retry_until:now()+86400,attempts:0,next_attempt:0,error:None,unread:false};
let view=message_view(d,&m);d.messages.push(m);Ok(view)}).await
    }
    pub async fn pending(
        &self,
        peer: &str,
        bundle: &Bundle,
        attachments: bool,
    ) -> Result<Option<Envelope>, RpcError> {
        let needs_update = self
            .0
            .vault
            .read(|d| {
                Ok(
                    d.peers.get(peer).and_then(|p| p.remote.as_ref()) != Some(bundle)
                        || d.messages.iter().any(|m| {
                            m.peer == peer
                                && m.direction == "outgoing"
                                && matches!(m.state.as_str(), "pending" | "retry_wait")
                                && m.next_attempt <= now()
                                && (attachments || m.body.attachment.is_none())
                        }),
                )
            })
            .await?;
        if !needs_update {
            return Ok(None);
        }
        self.0
            .vault
            .update(|d| {
                bind(d, peer, bundle)?;
                let Some(index) = d.messages.iter().position(|m| {
                    m.peer == peer
                        && m.direction == "outgoing"
                        && matches!(m.state.as_str(), "pending" | "retry_wait")
                        && m.next_attempt <= now()
                        && (attachments || m.body.attachment.is_none())
                }) else {
                    return Ok(None);
                };
                if d.messages[index].retry_until < now() || d.messages[index].attempts >= 3 {
                    let m = &mut d.messages[index];
                    m.state = "action_required".into();
                    m.error = Some("MESSAGE_RETRY_LIMIT".into());
                    return Ok(None);
                }
                if d.messages[index].envelope.is_none() {
                    let p = d.peers.get_mut(peer).ok_or_else(vault::locked)?;
                    let mut s = if let Some(s) = p.sessions.first() {
                        session(s)?
                    } else {
                        let a = account(d)?;
                        let curve = Curve25519PublicKey::from_base64(&bundle.curve)
                            .map_err(|_| vault::locked())?;
                        let otk = Curve25519PublicKey::from_base64(&bundle.one_time)
                            .map_err(|_| vault::locked())?;
                        a.create_outbound_session(SessionConfig::version_1(), curve, otk)
                            .map_err(|_| {
                                err(
                                    "CHAT_SESSION_FAILED",
                                    "The reviewed library rejected outbound session creation.",
                                )
                            })?
                    };
                    let plain = Zeroizing::new(
                        serde_json::to_vec(&d.messages[index].body)
                            .map_err(|_| RpcError::storage())?,
                    );
                    let encrypted = s.encrypt(plain.as_slice()).map_err(|_| {
                        err(
                            "CHAT_SESSION_FAILED",
                            "The reviewed library rejected message encryption.",
                        )
                    })?;
                    let (message_type, ciphertext) = encrypted.to_parts();
                    let envelope = Envelope {
                        version: 1,
                        message_id: d.messages[index].id.clone(),
                        sender: d.device.clone(),
                        recipient: peer.into(),
                        session_id: s.session_id(),
                        message_type,
                        ciphertext: B64.encode(ciphertext),
                    };
                    let p = d.peers.get_mut(peer).ok_or_else(vault::locked)?;
                    let record = StoredSession {
                        id: s.session_id(),
                        pickle: encode(&s.pickle())?,
                    };
                    if p.sessions.is_empty() {
                        p.sessions.push(record);
                    } else {
                        p.sessions[0] = record;
                    }
                    d.messages[index].digest = Some(digest(&envelope)?);
                    d.messages[index].envelope = Some(envelope);
                }
                let m = &mut d.messages[index];
                m.state = "retry_wait".into();
                m.attempts += 1;
                m.next_attempt = now() + 4;
                m.error = None;
                Ok(m.envelope.clone())
            })
            .await
    }
    pub async fn receive(
        &self,
        peer: &str,
        bundle: &Bundle,
        envelope: Envelope,
        attachments: bool,
    ) -> Result<Receipt, RpcError> {
        file_id(&envelope.message_id)?;
        if envelope.version != 1
            || envelope.sender != peer
            || envelope.ciphertext.len() > 13000
            || envelope.session_id.len() > 64
            || envelope.message_type > 1
        {
            return Err(err(
                "INVALID_MESSAGE",
                "The encrypted envelope exceeds the protocol bounds.",
            ));
        }
        let accepted_digest = digest(&envelope)?;
        self.0.vault.update(|d|{
   if envelope.recipient!=d.device{return Err(err("INVALID_MESSAGE","The envelope is addressed to another identity."));}bind(d,peer,bundle)?;
   if let Some(old)=d.messages.iter().find(|m|m.id==envelope.message_id){if old.direction!="incoming"||old.peer!=peer||old.digest.as_deref()!=Some(accepted_digest.as_str()){return Err(err("MESSAGE_ID_CONFLICT","The message ID has already been committed with a different envelope."));}return old.receipt.clone().ok_or_else(vault::locked);}
   if d.messages.len()>=MAX_CONTENT_MESSAGES{return Err(err("CONTENT_QUOTA","The protected inbox is full. No delivery receipt was issued."));}
   let raw=B64.decode(&envelope.ciphertext).map_err(|_|err("INVALID_MESSAGE","Invalid encrypted-message encoding."))?;if B64.encode(&raw)!=envelope.ciphertext{return Err(err("INVALID_MESSAGE","Non-canonical encrypted-message encoding."));}
let olm=OlmMessage::from_parts(envelope.message_type,&raw).map_err(|_|err("INVALID_MESSAGE","The reviewed library rejected the encrypted message."))?;
   let p=d.peers.get(peer).ok_or_else(vault::locked)?;
let found=p.sessions.iter().position(|s|s.id==envelope.session_id);
   let (s,plain)=if let Some(index)=found{let mut s=session(&p.sessions[index])?;
let plain=s.decrypt(&olm).map_err(|_|err("CHAT_DECRYPT_FAILED","Message authentication/replay checks failed. Nothing was acknowledged."))?;(s,plain)}else{
    if p.sessions.len()>=2{return Err(err("CHAT_SESSION_LIMIT","This peer's bounded session set is full. No session was reset."));}
    let OlmMessage::PreKey(prekey)=&olm else{return Err(err("CHAT_SESSION_MISSING","A matching E2EE session is required."));};
let mut a=account(d)?;
let curve=Curve25519PublicKey::from_base64(&bundle.curve).map_err(|_|err("CHAT_KEY_INVALID","Invalid sender E2EE key."))?;
let result=a.create_inbound_session(SessionConfig::version_1(),curve,prekey).map_err(|_|err("CHAT_SESSION_FAILED","Inbound session creation failed. Existing state was preserved."))?;d.account=encode(&a.pickle())?;(result.session,result.plaintext)
   };
   if s.session_id()!=envelope.session_id{return Err(err("CHAT_SESSION_FAILED","The envelope names a different cryptographic session."));}
   let plain=Zeroizing::new(plain);if plain.len()>8500{return Err(err("MESSAGE_TOO_LARGE","The authenticated message exceeded its plaintext bound."));}
let body:MessageBody=serde_json::from_slice(&plain).map_err(|_|err("INVALID_MESSAGE","The authenticated message schema is invalid."))?;
   if body.domain!="NARADA-CHAT-1"||body.message_id!=envelope.message_id||body.sender!=peer||body.recipient!=d.device{return Err(err("INVALID_MESSAGE","Inner and outer message identity bindings differ."));}chat_text(&body.text,body.attachment.is_some())?;
   if let Some(a)=&body.attachment{if !attachments{return Err(err("ACCESS_DENIED","Attachment consent is not granted."));}validate_attachment(a)?;if d.blobs.contains_key(&a.descriptor.blob_id){return Err(err("BLOB_ID_CONFLICT","This attachment ID already exists."));}d.blobs.insert(a.descriptor.blob_id.clone(),Blob{id:a.descriptor.blob_id.clone(),peer:peer.into(),direction:"incoming".into(),descriptor:a.descriptor.clone(),file_identity:None,state:"not_requested".into(),verified:vec![false;a.descriptor.hashes.len()],error:None,created:now(),export:None,export_id:None,export_destination:None});}
   let p=d.peers.get_mut(peer).ok_or_else(vault::locked)?;
let record=StoredSession{id:s.session_id(),pickle:encode(&s.pickle())?};if let Some(index)=found{p.sessions[index]=record;}else{p.sessions.push(record);}
   let receipt=Receipt{message_id:envelope.message_id.clone(),sender:peer.into(),recipient:d.device.clone(),digest:accepted_digest.clone(),state:"delivered".into(),committed_at:chrono::Utc::now().to_rfc3339()};d.messages.push(Message{id:envelope.message_id.clone(),peer:peer.into(),direction:"incoming".into(),state:"delivered".into(),request_hash:accepted_digest.clone(),body,envelope:None,digest:Some(accepted_digest),receipt:Some(receipt.clone()),created:now(),retry_until:now(),attempts:0,next_attempt:0,error:None,unread:true});Ok(receipt)
  }).await
    }
    pub async fn acknowledge(&self, peer: &str, receipt: Receipt) -> Result<(), RpcError> {
        file_id(&receipt.message_id)?;
        transfer_hash(&receipt.digest)?;
        if receipt.state != "delivered"
            || receipt.recipient != peer
            || chrono::DateTime::parse_from_rfc3339(&receipt.committed_at).is_err()
        {
            return Err(err("INVALID_RECEIPT", "The delivery receipt is invalid."));
        }
        self.0
            .vault
            .update(|d| {
                if receipt.sender != d.device {
                    return Err(err("INVALID_RECEIPT", "The receipt names another sender."));
                }
                let m = d
                    .messages
                    .iter_mut()
                    .find(|m| {
                        m.id == receipt.message_id && m.peer == peer && m.direction == "outgoing"
                    })
                    .ok_or_else(|| {
                        err(
                            "MESSAGE_UNAVAILABLE",
                            "No matching outgoing message exists.",
                        )
                    })?;
                if m.digest.as_deref() != Some(receipt.digest.as_str()) {
                    return Err(err(
                        "INVALID_RECEIPT",
                        "The receipt does not acknowledge the persisted ciphertext.",
                    ));
                }
                if let Some(old) = &m.receipt {
                    if old != &receipt {
                        return Err(err(
                            "INVALID_RECEIPT",
                            "A committed receipt cannot be replaced.",
                        ));
                    }
                    return Ok(());
                }
                m.receipt = Some(receipt);
                m.state = "delivered".into();
                m.error = None;
                Ok(())
            })
            .await
    }
}
