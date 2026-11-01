//! Small stateless age v1/X25519 adapter. Bounded RAM, no native handles, file/network I/O,
//! nonce reconstruction or plaintext fallback. Commit/transport/publication belong to Kotlin.
use age::secrecy::ExposeSecret;
use jni::{objects::{JByteArray,JClass,JObject},sys::{jboolean,jbyteArray,jobjectArray,jstring},JNIEnv};
use std::{io::{Read,Write},panic::{catch_unwind,AssertUnwindSafe},ptr};
use zeroize::Zeroizing;
const MAX_PLAIN:usize=8*1024*1024;
const MAX_CIPHER:usize=MAX_PLAIN+65536;
const INFO:&str="narada-age/1;age/0.12.1;x25519/v1;max-plain/8388608";
type Error=&'static str;
fn identity(value:&[u8])->Result<age::x25519::Identity,Error>{
    if value.is_empty()||value.len()>128{return Err("AGE_IDENTITY_INVALID")}
    let text=std::str::from_utf8(value).map_err(|_|"AGE_IDENTITY_INVALID")?;
    let key=text.parse::<age::x25519::Identity>().map_err(|_|"AGE_IDENTITY_INVALID")?;
    if key.to_string().expose_secret()!=text{return Err("AGE_IDENTITY_INVALID")}
    Ok(key)
}
fn encrypt(plain:&[u8])->Result<(Zeroizing<Vec<u8>>,Vec<u8>),Error>{
    if plain.len()>MAX_PLAIN{return Err("ATTACHMENT_LIMIT")}
    let key=age::x25519::Identity::generate();let public=key.to_public();
    let encryptor=age::Encryptor::with_recipients(std::iter::once(&public as &dyn age::Recipient)).map_err(|_|"AGE_ENCRYPT_FAILED")?;
    let mut cipher=Vec::new();let mut writer=encryptor.wrap_output(&mut cipher).map_err(|_|"AGE_ENCRYPT_FAILED")?;
    for chunk in plain.chunks(65536){writer.write_all(chunk).map_err(|_|"AGE_ENCRYPT_FAILED")?;}
    writer.finish().map_err(|_|"AGE_ENCRYPT_FAILED")?;
    if cipher.len()>MAX_CIPHER{return Err("ATTACHMENT_LIMIT")}
    Ok((Zeroizing::new(key.to_string().expose_secret().as_bytes().to_vec()),cipher))
}
fn decrypt(cipher:&[u8],encoded:&[u8])->Result<Zeroizing<Vec<u8>>,Error>{
    if cipher.is_empty()||cipher.len()>MAX_CIPHER{return Err("ATTACHMENT_LIMIT")}
    let key=identity(encoded)?;
    let decryptor=age::Decryptor::new(cipher).map_err(|_|"AGE_DECRYPT_FAILED")?;
    let reader=decryptor.decrypt(std::iter::once(&key as &dyn age::Identity)).map_err(|_|"AGE_DECRYPT_FAILED")?;
    let mut plain=Zeroizing::new(Vec::new());
    reader.take(MAX_PLAIN as u64+1).read_to_end(&mut plain).map_err(|_|"AGE_DECRYPT_FAILED")?;
    if plain.len()>MAX_PLAIN{return Err("ATTACHMENT_LIMIT")}
    Ok(plain)
}
fn bytes(env:&mut JNIEnv<'_>,array:&JByteArray<'_>,limit:usize)->Result<Zeroizing<Vec<u8>>,Error>{
    let size=env.get_array_length(array).map_err(|_|"AGE_INPUT_INVALID")?;
    if size<0||size as usize>limit{return Err("ATTACHMENT_LIMIT")}
    env.convert_byte_array(array).map(Zeroizing::new).map_err(|_|"AGE_INPUT_INVALID")
}
fn error(env:&mut JNIEnv<'_>,code:Error){
    // Never forward a library/debug error, identity, path or plaintext into Java/logs.
    if !env.exception_check().unwrap_or(true){let _=env.throw_new("java/lang/IllegalStateException",code);}
}
#[no_mangle]
pub extern "system" fn Java_com_narada_connection_NativeAgeBridge_info(mut env:JNIEnv,_class:JClass)->jstring{
    match env.new_string(INFO){Ok(s)=>s.into_raw(),Err(_)=>{error(&mut env,"AGE_UNAVAILABLE");ptr::null_mut()}}
}
#[no_mangle]
pub extern "system" fn Java_com_narada_connection_NativeAgeBridge_validIdentity(mut env:JNIEnv,_class:JClass,input:JByteArray)->jboolean{
    match catch_unwind(AssertUnwindSafe(||bytes(&mut env,&input,128).and_then(|b|identity(&b).map(|_|())))){
        Ok(Ok(()))=>1,_=>0
    }
}
#[no_mangle]
pub extern "system" fn Java_com_narada_connection_NativeAgeBridge_encrypt(mut env:JNIEnv,_class:JClass,input:JByteArray)->jobjectArray{
    let result=catch_unwind(AssertUnwindSafe(||{
        let plain=bytes(&mut env,&input,MAX_PLAIN)?;let (key,cipher)=encrypt(&plain)?;
        let array=env.new_object_array(2,"[B",JObject::null()).map_err(|_|"AGE_UNAVAILABLE")?;
        let jkey=env.byte_array_from_slice(&key).map_err(|_|"AGE_UNAVAILABLE")?;
        let jcipher=env.byte_array_from_slice(&cipher).map_err(|_|"AGE_UNAVAILABLE")?;
        env.set_object_array_element(&array,0,jkey).map_err(|_|"AGE_UNAVAILABLE")?;
        env.set_object_array_element(&array,1,jcipher).map_err(|_|"AGE_UNAVAILABLE")?;
        Ok::<_,Error>(array.into_raw())
    }));
    match result{Ok(Ok(v))=>v,Ok(Err(e))=>{error(&mut env,e);ptr::null_mut()},Err(_)=>{error(&mut env,"AGE_UNAVAILABLE");ptr::null_mut()}}
}
#[no_mangle]
pub extern "system" fn Java_com_narada_connection_NativeAgeBridge_decrypt(mut env:JNIEnv,_class:JClass,input:JByteArray,secret:JByteArray)->jbyteArray{
    let result=catch_unwind(AssertUnwindSafe(||{
        let cipher=bytes(&mut env,&input,MAX_CIPHER)?;let key=bytes(&mut env,&secret,128)?;
        let plain=decrypt(&cipher,&key)?;
        env.byte_array_from_slice(&plain).map(|v|v.into_raw()).map_err(|_|"AGE_UNAVAILABLE")
    }));
    match result{Ok(Ok(v))=>v,Ok(Err(e))=>{error(&mut env,e);ptr::null_mut()},Err(_)=>{error(&mut env,"AGE_UNAVAILABLE");ptr::null_mut()}}
}
#[cfg(test)]
mod tests{
    use super::*;
    #[test]fn empty_file_round_trip(){let(k,c)=encrypt(&[]).unwrap();assert_eq!(*decrypt(&c,&k).unwrap(),Vec::<u8>::new());}
    #[test]fn binary_multi_record_round_trip(){let p=(0..300001).map(|i|(i%251)as u8).collect::<Vec<_>>();let(k,c)=encrypt(&p).unwrap();assert_eq!(&*decrypt(&c,&k).unwrap(),&p);}
    #[test]fn each_encryption_has_fresh_identity_and_cipher(){let(a,x)=encrypt(b"same").unwrap();let(b,y)=encrypt(b"same").unwrap();assert_ne!(&*a,&*b);assert_ne!(x,y);}
    #[test]fn wrong_identity_is_refused(){let(_,x)=encrypt(b"first").unwrap();let(k,_)=encrypt(b"second").unwrap();assert!(decrypt(&x,&k).is_err());}
    #[test]fn header_tampering_is_refused(){let(k,mut x)=encrypt(b"body").unwrap();x[0]^=1;assert!(decrypt(&x,&k).is_err());}
    #[test]fn body_tampering_is_refused(){let(k,mut x)=encrypt(&vec![23;100000]).unwrap();let i=x.len()/2;x[i]^=1;assert!(decrypt(&x,&k).is_err());}
    #[test]fn final_record_truncation_is_refused(){let(k,mut x)=encrypt(&vec![8;65536]).unwrap();x.pop();assert!(decrypt(&x,&k).is_err());}
    #[test]fn reordered_records_are_refused(){let(k,mut x)=encrypt(&vec![6;200000]).unwrap();for i in 0..65552{x.swap(400+i,400+65552+i)}assert!(decrypt(&x,&k).is_err());}
    #[test]fn trailing_bytes_are_refused(){let(k,mut x)=encrypt(&vec![11;65536]).unwrap();x.extend_from_slice(b"extra");assert!(decrypt(&x,&k).is_err());}
    #[test]fn size_limits_precede_encryption(){assert!(encrypt(&vec![0;MAX_PLAIN+1]).is_err());assert!(decrypt(&vec![0;MAX_CIPHER+1],b"bad").is_err());}
    #[test]fn secret_is_canonical_and_bounded(){let(k,_)=encrypt(b"a").unwrap();assert!(identity(&k).is_ok());let mut changed=k.to_vec();changed.push(b' ');assert!(identity(&changed).is_err());assert!(identity(&vec![0;129]).is_err());}
    #[test]fn exact_plain_limit_round_trip(){let p=vec![97;MAX_PLAIN];let(k,c)=encrypt(&p).unwrap();assert!(c.len()<=MAX_CIPHER);assert_eq!(decrypt(&c,&k).unwrap().len(),MAX_PLAIN);}
}
