//! Stateless, bounded JNI adapter to the desktop's exact vodozemac Olm v1 mode.
//! No global accounts/sessions, file/network I/O, logs, handles, ratchet implementation or fallback.
//! Every returned candidate must be committed by the protected Kotlin aggregate BEFORE send/ACK.
use base64::{engine::general_purpose::STANDARD as B64, Engine};
use jni::{objects::{JByteArray, JClass}, sys::jbyteArray, JNIEnv};
use serde::{Deserialize, Serialize};
use serde_json::{json, Value};
use std::{panic::{catch_unwind, AssertUnwindSafe}, sync::Once};
use vodozemac::{olm::{Account, AccountPickle, OlmMessage, Session, SessionConfig, SessionPickle}, Curve25519PublicKey, Ed25519PublicKey, Ed25519Signature};
use zeroize::Zeroizing;

const MAX_IO: usize = 524_288;
const MAX_ACCOUNT: usize = 65_536;
const MAX_SESSION: usize = 98_304;
type R<T> = Result<T, &'static str>;
#[derive(Clone, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
struct Bundle { version: u16, transport_id: String, recipient: String, curve: String, ed: String, one_time: String, signature: String }
#[derive(Clone, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
struct StoredSession { id: String, pickle: String }
#[derive(Clone, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
struct Envelope { version: u16, message_id: String, sender: String, recipient: String, session_id: String, message_type: usize, ciphertext: String }
#[derive(Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
struct Body { domain: String, message_id: String, sender: String, recipient: String, text: String, attachment: Option<Attachment> }
#[derive(Clone,Serialize,Deserialize)]
#[serde(deny_unknown_fields)]
struct BlobDescriptor { version:u16, blob_id:String, size:u64, sha256:String, hashes:Vec<String> }
#[derive(Clone,Serialize,Deserialize)]
#[serde(deny_unknown_fields)]
struct Attachment { descriptor:BlobDescriptor, name:String, plaintext_size:u64, plaintext_sha256:String, identity:String }
fn attachment(a:&Attachment)->R<()> {
    let d=&a.descriptor;
    need(d.version==1 && uuid(&d.blob_id) && d.size>0 && d.size<=8454144 && id(&d.sha256)
        && d.hashes.len()==d.size.div_ceil(262144) as usize && d.hashes.iter().all(|h|id(h))
        && !a.name.is_empty() && a.name.len()<=180 && !a.name.chars().any(|c|c.is_control()||c=='/'||c=='\\')
        && a.plaintext_size<=8388608 && id(&a.plaintext_sha256) && a.identity.len()<=128 && a.identity.starts_with("AGE-SECRET-KEY-1")
        && a.identity.bytes().all(|c| c.is_ascii_uppercase()||c.is_ascii_digit()||c==b'-'), "INVALID_MESSAGE")
    // Canonical key decoding is additionally enforced by age JNI BEFORE Kotlin commits/ACKs.
}
#[derive(Deserialize)]
#[serde(tag="op", rename_all="snake_case", deny_unknown_fields)]
enum Operation {
    Info {},
    NewAccount {},
    InspectAccount { account: String },
    InspectSession { session: StoredSession },
    Bundle { account: String, local_id: String, peer_id: String },
    VerifyBundle { bundle: Bundle, local_id: String, peer_id: String },
    Encrypt { account: String, ours: Bundle, remote: Bundle, session: Option<StoredSession>, body: Body },
    Decrypt { account: String, ours: Bundle, remote: Bundle, sessions: Vec<StoredSession>, envelope: Envelope },
}
fn need(ok: bool, code: &'static str) -> R<()> { if ok { Ok(()) } else { Err(code) } }
fn id(s: &str) -> bool { s.len()==64 && s.bytes().all(|c| c.is_ascii_digit() || (b'a'..=b'f').contains(&c)) }
fn uuid(s: &str) -> bool { s.len()==36 && s.bytes().enumerate().all(|(i,c)| if [8,13,18,23].contains(&i) {c==b'-'} else {c.is_ascii_digit() || (b'a'..=b'f').contains(&c)}) }
fn text(s: &str) -> R<()> { need(s.len()<=4096 && !s.trim().is_empty() && !s.chars().any(|c| c.is_control() && c!='\n' && c!='\t'), "INVALID_MESSAGE") }
fn encode<T: Serialize>(t: &T, max: usize) -> R<String> { let s=serde_json::to_string(t).map_err(|_| "CRYPTO_STATE_INVALID")?; need(s.len()<=max,"CHAT_STATE_LIMIT")?;Ok(s) }
fn account(s: &str) -> R<Account> {
    need(!s.is_empty() && s.len()<=MAX_ACCOUNT,"CRYPTO_STATE_INVALID")?;
    let p: AccountPickle=serde_json::from_str(s).map_err(|_|"CRYPTO_STATE_INVALID")?;Ok(Account::from_pickle(p))
}
fn session(s: &StoredSession) -> R<Session> {
    need(!s.id.is_empty() && s.id.len()<=64 && !s.pickle.is_empty() && s.pickle.len()<=MAX_SESSION,"CRYPTO_STATE_INVALID")?;
    let p: SessionPickle=serde_json::from_str(&s.pickle).map_err(|_|"CRYPTO_STATE_INVALID")?;
    let result=Session::from_pickle(p);need(result.session_id()==s.id && result.session_config()==SessionConfig::version_1(),"CRYPTO_STATE_INVALID")?;Ok(result)
}
fn stored(s: &Session) -> R<StoredSession> { Ok(StoredSession{id:s.session_id(),pickle:encode(&s.pickle(),MAX_SESSION)?}) }
fn statement(b: &Bundle) -> R<Vec<u8>> { serde_json::to_vec(&("NARADA-CHAT-BUNDLE-1",b.version,&b.transport_id,&b.recipient,&b.curve,&b.ed,&b.one_time)).map_err(|_|"CHAT_KEY_INVALID") }
fn verify(b: &Bundle, local: &str, peer: &str) -> R<()> {
    need(id(local) && id(peer) && local!=peer && b.version==1 && b.transport_id==peer && b.recipient==local && b.curve.len()<=44 && b.ed.len()<=44 && b.one_time.len()<=44 && b.signature.len()<=88,"CHAT_KEY_INVALID")?;
    let ed=Ed25519PublicKey::from_base64(&b.ed).map_err(|_|"CHAT_KEY_INVALID")?;
    let sig=Ed25519Signature::from_base64(&b.signature).map_err(|_|"CHAT_KEY_INVALID")?;
    let curve=Curve25519PublicKey::from_base64(&b.curve).map_err(|_|"CHAT_KEY_INVALID")?;
    let otk=Curve25519PublicKey::from_base64(&b.one_time).map_err(|_|"CHAT_KEY_INVALID")?;
    need(ed.to_base64()==b.ed && sig.to_base64()==b.signature && curve.to_base64()==b.curve && otk.to_base64()==b.one_time,"CHAT_KEY_INVALID")?;
    ed.verify(&statement(b)?,&sig).map_err(|_|"CHAT_KEY_INVALID")
}
fn local(a: &Account, ours: &Bundle, remote: &Bundle) -> R<()> {
    verify(ours,&remote.transport_id,&ours.transport_id)?;
    verify(remote,&ours.transport_id,&remote.transport_id)?;
    need(ours.recipient==remote.transport_id && a.curve25519_key().to_base64()==ours.curve && a.ed25519_key().to_base64()==ours.ed,"CRYPTO_STATE_INVALID")
}
fn body(b: &Body, sender: &str, recipient: &str, message: &str) -> R<()> {
    need(b.domain=="NARADA-CHAT-1" && b.sender==sender && b.recipient==recipient && b.message_id==message && uuid(message),"INVALID_MESSAGE")?;
    if let Some(a)=&b.attachment { attachment(a)?;need(b.text.len()<=4096 && !b.text.chars().any(|c|c.is_control()&&c!='\n'&&c!='\t'),"INVALID_MESSAGE") }
    else { text(&b.text) }
}
fn apply(op: Operation) -> R<Value> {
    match op {
        Operation::Info {} => Ok(json!({"bridge_version":2,"vodozemac":"0.11.1","mode":"olm.v1","mac_bits":64,"experimental_session_config":false})),
        Operation::NewAccount {} => {
            let a=Account::new();Ok(json!({"account":encode(&a.pickle(),MAX_ACCOUNT)?,"curve":a.curve25519_key().to_base64(),"ed":a.ed25519_key().to_base64()}))
        }
        Operation::InspectAccount { account: value } => {
            let a=account(&value)?;Ok(json!({"curve":a.curve25519_key().to_base64(),"ed":a.ed25519_key().to_base64()}))
        }
        Operation::InspectSession { session: value } => { let s=session(&value)?; Ok(json!({"id":s.session_id(),"mac_bits":64})) }
        Operation::Bundle { account: value,local_id,peer_id } => {
            need(id(&local_id) && id(&peer_id) && local_id!=peer_id,"CHAT_KEY_INVALID")?;
            let mut a=account(&value)?;a.generate_one_time_keys(1);
            let key=a.one_time_keys().values().next().ok_or("CRYPTO_UNAVAILABLE")?.to_base64();
            let mut b=Bundle{version:1,transport_id:local_id,recipient:peer_id,curve:a.curve25519_key().to_base64(),ed:a.ed25519_key().to_base64(),one_time:key,signature:String::new()};
            b.signature=a.sign(statement(&b)?).to_base64();a.mark_keys_as_published();
            Ok(json!({"account":encode(&a.pickle(),MAX_ACCOUNT)?,"bundle":b}))
        }
        Operation::VerifyBundle { bundle,local_id,peer_id } => {verify(&bundle,&local_id,&peer_id)?;Ok(json!({"valid":true}))}
        Operation::Encrypt { account: value,ours,remote,session: old,body: plain } => {
            let a=account(&value)?;local(&a,&ours,&remote)?;body(&plain,&ours.transport_id,&remote.transport_id,&plain.message_id)?;
            let mut s=if let Some(old)=old {session(&old)?} else {
                let curve=Curve25519PublicKey::from_base64(&remote.curve).map_err(|_|"CHAT_KEY_INVALID")?;
                let otk=Curve25519PublicKey::from_base64(&remote.one_time).map_err(|_|"CHAT_KEY_INVALID")?;
                a.create_outbound_session(SessionConfig::version_1(),curve,otk).map_err(|_|"CHAT_SESSION_FAILED")?
            };
            let bytes=Zeroizing::new(serde_json::to_vec(&plain).map_err(|_|"INVALID_MESSAGE")?);need(bytes.len()<=8500,"INVALID_MESSAGE")?;
            let message=s.encrypt(bytes.as_slice()).map_err(|_|"CHAT_SESSION_FAILED")?;let (message_type,ciphertext)=message.to_parts();
            let envelope=Envelope{version:1,message_id:plain.message_id,sender:ours.transport_id,recipient:remote.transport_id,session_id:s.session_id(),message_type,ciphertext:B64.encode(ciphertext)};
            need(envelope.ciphertext.len()<=13000,"INVALID_MESSAGE")?;
            Ok(json!({"session":stored(&s)?,"envelope":envelope}))
        }
        Operation::Decrypt { account: value,ours,remote,sessions,envelope:e } => {
            need(sessions.len()<=2 && sessions.iter().enumerate().all(|(i,s)| sessions[..i].iter().all(|old|old.id!=s.id)),"CRYPTO_STATE_INVALID")?;
            let mut a=account(&value)?;local(&a,&ours,&remote)?;
            need(e.version==1 && uuid(&e.message_id) && e.sender==remote.transport_id && e.recipient==ours.transport_id && !e.session_id.is_empty() && e.session_id.len()<=64 && e.message_type<=1 && !e.ciphertext.is_empty() && e.ciphertext.len()<=13000,"INVALID_MESSAGE")?;
            let raw=B64.decode(&e.ciphertext).map_err(|_|"INVALID_MESSAGE")?;need(B64.encode(&raw)==e.ciphertext,"INVALID_MESSAGE")?;
            let message=OlmMessage::from_parts(e.message_type,&raw).map_err(|_|"INVALID_MESSAGE")?;
            let (s,bytes)=if let Some(old)=sessions.iter().find(|s|s.id==e.session_id) {
                let mut s=session(old)?;let plain=s.decrypt(&message).map_err(|_|"CHAT_DECRYPT_FAILED")?;(s,plain)
            } else {
                need(sessions.len()<2,"CHAT_SESSION_LIMIT")?;
                let OlmMessage::PreKey(prekey)=&message else {return Err("CHAT_SESSION_MISSING")};
                let curve=Curve25519PublicKey::from_base64(&remote.curve).map_err(|_|"CHAT_KEY_INVALID")?;
                let r=a.create_inbound_session(SessionConfig::version_1(),curve,prekey).map_err(|_|"CHAT_SESSION_FAILED")?;(r.session,r.plaintext)
            };
            need(s.session_id()==e.session_id,"CHAT_SESSION_FAILED")?;
            let bytes=Zeroizing::new(bytes);need(bytes.len()<=8500,"INVALID_MESSAGE")?;
            let plain:Body=serde_json::from_slice(&bytes).map_err(|_|"INVALID_MESSAGE")?;body(&plain,&e.sender,&e.recipient,&e.message_id)?;
            Ok(json!({"account":encode(&a.pickle(),MAX_ACCOUNT)?,"session":stored(&s)?,"text":plain.text,"attachment":plain.attachment}))
        }
    }
}
fn response(input: &[u8]) -> Vec<u8> {
    let result=catch_unwind(AssertUnwindSafe(|| {
        need(!input.is_empty() && input.len()<=MAX_IO,"CRYPTO_INPUT_INVALID")?;
        let op:Operation=serde_json::from_slice(input).map_err(|_|"CRYPTO_INPUT_INVALID")?;apply(op)
    })).unwrap_or(Err("CRYPTO_UNAVAILABLE"));
    let value=match result {Ok(value)=>json!({"ok":true,"value":value,"error":null}),Err(code)=>json!({"ok":false,"value":null,"error":code})};
    let bytes=serde_json::to_vec(&value).unwrap_or_else(|_|b"{\"ok\":false,\"value\":null,\"error\":\"CRYPTO_UNAVAILABLE\"}".to_vec());
    if bytes.len()>MAX_IO {b"{\"ok\":false,\"value\":null,\"error\":\"CHAT_STATE_LIMIT\"}".to_vec()} else {bytes}
}
/// JNI buffers are copied into bounded zeroizing Rust buffers. Immutable Java strings and
/// serialization/library temporaries are NOT claimed to have guaranteed memory erasure.
#[no_mangle]
pub extern "system" fn Java_com_narada_connection_NativeOlmBridge_call(mut env:JNIEnv, _class:JClass, input:JByteArray)->jbyteArray {
    static HOOK:Once=Once::new();HOOK.call_once(||std::panic::set_hook(Box::new(|_|{})));
    let result=catch_unwind(AssertUnwindSafe(|| -> Result<jbyteArray,()> {
        let size=env.get_array_length(&input).map_err(|_|())?;
        if size<=0 || size as usize>MAX_IO {return Err(())}
        let input=Zeroizing::new(env.convert_byte_array(input).map_err(|_|())?);
        let output=Zeroizing::new(response(&input));
        Ok(env.byte_array_from_slice(&output).map_err(|_|())?.into_raw())
    }));
    match result {Ok(Ok(array))=>array,_=>{let _=env.throw_new("java/lang/IllegalStateException","NARADA_OLM_UNAVAILABLE");std::ptr::null_mut()}}
}

#[cfg(test)]
mod tests {
    use super::*;
    fn run(value:Value)->Value {let b=response(&serde_json::to_vec(&value).unwrap());let r:Value=serde_json::from_slice(&b).unwrap();assert_eq!(r["ok"],true,"native operation failed without secret diagnostics");r["value"].clone()}
    fn pair()->(Value,Value) {
        let a=run(json!({"op":"new_account"}));let b=run(json!({"op":"new_account"}));
        (run(json!({"op":"bundle","account":a["account"],"local_id":"a".repeat(64),"peer_id":"b".repeat(64)})),run(json!({"op":"bundle","account":b["account"],"local_id":"b".repeat(64),"peer_id":"a".repeat(64)})))
    }
    fn plain(id:&str,text:&str)->Value {json!({"domain":"NARADA-CHAT-1","message_id":id,"sender":"a".repeat(64),"recipient":"b".repeat(64),"text":text,"attachment":null})}
    fn encrypted(a:&Value,b:&Value)->Value {run(json!({"op":"encrypt","account":a["account"],"ours":a["bundle"],"remote":b["bundle"],"session":null,"body":plain("00000000-0000-4000-8000-000000000001","నమస్కారం\nHello") }))}
    fn err(v:Value)->String {let r:Value=serde_json::from_slice(&response(&serde_json::to_vec(&v).unwrap())).unwrap();assert_eq!(r["ok"],false);r["error"].as_str().unwrap().into()}
    #[test] fn exact_library_and_compatibility_mode(){let i=run(json!({"op":"info"}));assert_eq!(i["vodozemac"],"0.11.1");assert_eq!(i["mac_bits"],64);assert_eq!(i["experimental_session_config"],false);}
    #[test] fn signed_bundles_are_transport_bound(){let(a,b)=pair();run(json!({"op":"verify_bundle","bundle":a["bundle"],"local_id":"b".repeat(64),"peer_id":"a".repeat(64)}));assert_eq!(err(json!({"op":"verify_bundle","bundle":b["bundle"],"local_id":"c".repeat(64),"peer_id":"b".repeat(64)})),"CHAT_KEY_INVALID");}
    #[test] fn unknown_and_duplicate_fields_are_rejected(){for s in [br#"{"op":"info","secret":1}"#.as_slice(),br#"{"op":"info","op":"info"}"#.as_slice()] {let r:Value=serde_json::from_slice(&response(s)).unwrap();assert_eq!(r["ok"],false);}}
    #[test] fn damaged_account_never_creates_a_replacement(){assert_eq!(err(json!({"op":"inspect_account","account":"{}"})),"CRYPTO_STATE_INVALID");}
    #[test] fn utf8_roundtrip_and_pickle_reopen(){let(a,b)=pair();let e=encrypted(&a,&b);let d=run(json!({"op":"decrypt","account":b["account"],"ours":b["bundle"],"remote":a["bundle"],"sessions":[],"envelope":e["envelope"]}));assert_eq!(d["text"],"నమస్కారం\nHello");let mut body=plain("00000000-0000-4000-8000-000000000002","Reply");body["sender"]=json!("b".repeat(64));body["recipient"]=json!("a".repeat(64));let reply=run(json!({"op":"encrypt","account":d["account"],"ours":b["bundle"],"remote":a["bundle"],"session":d["session"],"body":body}));let got=run(json!({"op":"decrypt","account":a["account"],"ours":a["bundle"],"remote":b["bundle"],"sessions":[e["session"]],"envelope":reply["envelope"]}));assert_eq!(got["text"],"Reply");}
    #[test] fn altered_ciphertext_cannot_be_authenticated(){let(a,b)=pair();let mut e=encrypted(&a,&b);let mut bytes=B64.decode(e["envelope"]["ciphertext"].as_str().unwrap()).unwrap();let n=bytes.len()-1;bytes[n]^=1;e["envelope"]["ciphertext"]=json!(B64.encode(bytes));assert!(matches!(err(json!({"op":"decrypt","account":b["account"],"ours":b["bundle"],"remote":a["bundle"],"sessions":[],"envelope":e["envelope"]})).as_str(),"CHAT_SESSION_FAILED"|"CHAT_DECRYPT_FAILED"));}
    #[test] fn replay_requires_the_durable_kotlin_receipt_not_ratchet_decryption(){let(a,b)=pair();let e=encrypted(&a,&b);let d=run(json!({"op":"decrypt","account":b["account"],"ours":b["bundle"],"remote":a["bundle"],"sessions":[],"envelope":e["envelope"]}));assert_eq!(err(json!({"op":"decrypt","account":d["account"],"ours":b["bundle"],"remote":a["bundle"],"sessions":[d["session"]],"envelope":e["envelope"]})),"CHAT_DECRYPT_FAILED");}
    #[test] fn outer_inner_identifier_mismatch_is_rejected(){let(a,b)=pair();let mut e=encrypted(&a,&b);e["envelope"]["message_id"]=json!("00000000-0000-4000-8000-000000000099");assert_eq!(err(json!({"op":"decrypt","account":b["account"],"ours":b["bundle"],"remote":a["bundle"],"sessions":[],"envelope":e["envelope"]})),"INVALID_MESSAGE");}
    #[test] fn session_pickle_and_identifier_must_agree(){let(a,b)=pair();let mut e=encrypted(&a,&b);e["session"]["id"]=json!("wrong");assert_eq!(err(json!({"op":"encrypt","account":a["account"],"ours":a["bundle"],"remote":b["bundle"],"session":e["session"],"body":plain("00000000-0000-4000-8000-000000000002","next")})),"CRYPTO_STATE_INVALID");}
    #[test] fn signed_zero_order_dh_key_is_rejected_by_library(){let(a,b)=pair();let mut remote:Bundle=serde_json::from_value(b["bundle"].clone()).unwrap();remote.curve=Curve25519PublicKey::from([0u8;32]).to_base64();let signer=account(b["account"].as_str().unwrap()).unwrap();remote.signature=signer.sign(statement(&remote).unwrap()).to_base64();assert_eq!(err(json!({"op":"encrypt","account":a["account"],"ours":a["bundle"],"remote":remote,"session":null,"body":plain("00000000-0000-4000-8000-000000000001","blocked")})),"CHAT_SESSION_FAILED");}
    #[test] fn plaintext_and_attachment_bounds_are_explicit(){let(a,b)=pair();let mut body=plain("00000000-0000-4000-8000-000000000001",&"é".repeat(2049));assert_eq!(err(json!({"op":"encrypt","account":a["account"],"ours":a["bundle"],"remote":b["bundle"],"session":null,"body":body})),"INVALID_MESSAGE");body["text"]=json!("text");body["attachment"]=json!({"secret":"not enabled"});assert_eq!(err(json!({"op":"encrypt","account":a["account"],"ours":a["bundle"],"remote":b["bundle"],"session":null,"body":body})),"CRYPTO_INPUT_INVALID");}
    #[test] fn oversized_input_is_rejected_without_parsing(){let r:Value=serde_json::from_slice(&response(&vec![b' ';MAX_IO+1])).unwrap();assert_eq!(r["error"],"CRYPTO_INPUT_INVALID");}
    // Synthetic descriptor/key spelling: these are typed-body/ratchet vectors, not age key
    // qualification. Actual key decoding is covered by native-age/JNI and actual-agent interop.
    fn typed_attachment()->Value { json!({"descriptor":{"version":1,"blob_id":"00000000-0000-4000-8000-000000000003","size":400,"sha256":"c".repeat(64),"hashes":["d".repeat(64)]},"name":"నమూనా.bin","plaintext_size":12,"plaintext_sha256":"e".repeat(64),"identity":format!("AGE-SECRET-KEY-1{}","Q".repeat(59))}) }
    fn attachment_body(text:&str)->Value {let mut b=plain("00000000-0000-4000-8000-000000000001",text);b["attachment"]=typed_attachment();b}
    fn encrypt_body(a:&Value,b:&Value,body:Value)->Value { run(json!({"op":"encrypt","account":a["account"],"ours":a["bundle"],"remote":b["bundle"],"session":null,"body":body})) }
    fn decrypt_body(a:&Value,b:&Value,e:&Value)->Value {run(json!({"op":"decrypt","account":b["account"],"ours":b["bundle"],"remote":a["bundle"],"sessions":[],"envelope":e["envelope"]}))}
    fn body_error(a:&Value,b:&Value,body:Value)->String {err(json!({"op":"encrypt","account":a["account"],"ours":a["bundle"],"remote":b["bundle"],"session":null,"body":body}))}
    #[test] fn typed_attachment_roundtrip_all_secret_fields(){let(a,b)=pair();let body=attachment_body("Private caption");let e=encrypt_body(&a,&b,body.clone());let got=decrypt_body(&a,&b,&e);assert_eq!(got["text"],body["text"]);assert_eq!(got["attachment"],body["attachment"]);assert!(!e["envelope"].to_string().contains("AGE-SECRET-KEY"));}
    #[test] fn empty_caption_is_valid_only_with_typed_attachment(){let(a,b)=pair();let body=attachment_body("");let e=encrypt_body(&a,&b,body.clone());assert_eq!(decrypt_body(&a,&b,&e)["attachment"],body["attachment"]);assert_eq!(body_error(&a,&b,plain("00000000-0000-4000-8000-000000000001","")),"INVALID_MESSAGE");}
    #[test] fn maximum_descriptor_and_worst_escaped_phone_caption_fit_wire(){let(a,b)=pair();let mut body=attachment_body(&"\t".repeat(1024));body["attachment"]["name"]=json!("\"".repeat(180));body["attachment"]["descriptor"]["size"]=json!(8454144);body["attachment"]["descriptor"]["hashes"]=json!(vec!["d".repeat(64);33]);body["attachment"]["identity"]=json!(format!("AGE-SECRET-KEY-1{}","Q".repeat(112)));body["attachment"]["plaintext_size"]=json!(8388608);assert!(serde_json::to_vec(&body).unwrap().len()<=8500);let e=encrypt_body(&a,&b,body.clone());assert!(e["envelope"]["ciphertext"].as_str().unwrap().len()<=13000);assert_eq!(decrypt_body(&a,&b,&e)["attachment"],body["attachment"]);}
    #[test] fn typed_attachment_rejects_unknown_nested_secret_fields(){let(a,b)=pair();let mut body=attachment_body("");body["attachment"]["extra_secret"]=json!("not allowed");assert_eq!(body_error(&a,&b,body),"CRYPTO_INPUT_INVALID");}
    #[test] fn typed_attachment_rejects_descriptor_hash_count_confusion(){let(a,b)=pair();let mut body=attachment_body("");body["attachment"]["descriptor"]["size"]=json!(262145);assert_eq!(body_error(&a,&b,body),"INVALID_MESSAGE");}
    #[test] fn typed_attachment_rejects_path_names_and_oversize_plaintext(){let(a,b)=pair();for name in ["../private", "x\\y", "bad\nname"] {let mut body=attachment_body("");body["attachment"]["name"]=json!(name);assert_eq!(body_error(&a,&b,body),"INVALID_MESSAGE");}let mut body=attachment_body("");body["attachment"]["plaintext_size"]=json!(8388609);assert_eq!(body_error(&a,&b,body),"INVALID_MESSAGE");}
    #[test] fn attachment_reply_reopens_the_same_persisted_olm_session(){let(a,b)=pair();let e=encrypt_body(&a,&b,attachment_body("first"));let d=decrypt_body(&a,&b,&e);let mut body=attachment_body("reply");body["message_id"]=json!("00000000-0000-4000-8000-000000000002");body["sender"]=json!("b".repeat(64));body["recipient"]=json!("a".repeat(64));let reply=run(json!({"op":"encrypt","account":d["account"],"ours":b["bundle"],"remote":a["bundle"],"session":d["session"],"body":body}));let got=run(json!({"op":"decrypt","account":a["account"],"ours":a["bundle"],"remote":b["bundle"],"sessions":[e["session"]],"envelope":reply["envelope"]}));assert_eq!(got["attachment"],body["attachment"]);assert_eq!(got["text"],"reply");}
    #[test] fn attachment_payload_corruption_is_not_a_partial_secret(){let(a,b)=pair();let mut e=encrypt_body(&a,&b,attachment_body("private"));let mut bytes=B64.decode(e["envelope"]["ciphertext"].as_str().unwrap()).unwrap();let n=bytes.len()-1;bytes[n]^=1;e["envelope"]["ciphertext"]=json!(B64.encode(bytes));assert!(matches!(err(json!({"op":"decrypt","account":b["account"],"ours":b["bundle"],"remote":a["bundle"],"sessions":[],"envelope":e["envelope"]})).as_str(),"CHAT_SESSION_FAILED"|"CHAT_DECRYPT_FAILED"));}

}
