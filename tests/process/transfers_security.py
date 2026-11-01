#!/usr/bin/env python3
"""Phase 4 single-pass real-process fixtures. Never use real profiles or valuable roots.
Independent OpenSSL/mTLS peer plus separate Rust agents; loopback development only.
"""
import hashlib, importlib.util, json, os, sqlite3, tempfile, time, unittest, uuid
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2]
spec=importlib.util.spec_from_file_location('pairing_security',ROOT/'tests/process/pairing_security.py')
p2=importlib.util.module_from_spec(spec);spec.loader.exec_module(p2)
CHUNK=256*1024

def manifest(data, name='received.bin', full_hash=None):
    hashes=[hashlib.sha256(data[i:i+CHUNK]).hexdigest() for i in range(0,len(data),CHUNK)]
    m={'version':1,'manifest_id':'','source_version':'b'*64,'name':name,'size':str(len(data)),'chunk_size':CHUNK,'chunk_count':len(hashes),'sha256':full_hash or hashlib.sha256(data).hexdigest()}
    canonical=[m[k] for k in ['version','source_version','name','size','chunk_size','chunk_count','sha256']]+[hashes]
    m['manifest_id']=hashlib.sha256(json.dumps(canonical,ensure_ascii=False,separators=(',',':')).encode()).hexdigest()
    return m,hashes

class TransfersTests(unittest.TestCase):
    def setUp(self):
        (ROOT/'test-fixtures').mkdir(exist_ok=True)
        self.temp=tempfile.TemporaryDirectory(prefix='transfers-',dir=ROOT/'test-fixtures');self.base=Path(self.temp.name)
        self.share=self.base/'approved';self.share.mkdir();self.data=bytes(range(251))*2200
        (self.share/'payload.bin').write_bytes(self.data)
        self.a=p2.Agent(self.base/'host-profile');self.b=None;self.peer=None
        self.root=self.a.call('register_root',{'path':str(self.share),'label':'Transfer fixtures','exclusions':[]})['root_id']
    def tearDown(self):
        if self.peer:self.peer.close()
        if self.b:self.b.stop()
        self.a.stop();self.temp.cleanup()
    def entry(self,agent=None,root=None,name='payload.bin'):
        return next(e for e in (agent or self.a).call('file_query',{'kind':'browse','root_id':root or self.root,'directory_id':None,'cursor':None,'sort':'name'})['entries'] if e['name']==name)
    def source(self,agent=None,root=None):
        e=self.entry(agent,root);return {'root_id':root or self.root,'object_id':e['object_id'],'expected_version':e['version']}
    def pair_raw(self,caps=None):
        self.a.enable();code=json.loads(self.a.invite()['code']);self.peer=p2.RawPeer(self.base/'raw-peer');claim=self.peer.claim(code);self.assertTrue(claim['ok'],claim)
        self.endpoint=f"127.0.0.1:{claim['data']['api_port']}";self.pin=code['fingerprint'];self.peer.connect(self.endpoint,self.pin,True);self.assertTrue(self.peer.proof()['ok'])
        self.a.call('approve_pairing',{'attempt_id':self.peer.attempt,'permissions':{'chat':False,'attachment':False,'roots':[{'root_id':self.root,'capabilities':caps or ['browse','read','create']}]}});self.assertTrue(self.peer.proof(True)['ok'])
    def reconnect(self):
        snap=self.a.snapshot();self.endpoint=snap['endpoint'];self.peer.connect(self.endpoint,snap['identity']['fingerprint'],True)
    def wire(self,kind,**data):
        time.sleep(.13)
        return self.peer.post('/api/v1/transfers',{'hello':p2.HELLO,'version':1,'action':{'kind':kind,**data}})
    def ok(self,kind,**data):
        r=self.wire(kind,**data);self.assertTrue(r['ok'],r);return r['data']
    def binary(self,tid,index,data=None,**extra):
        time.sleep(.13);body=b'' if data is None else data
        headers={'Content-Type':'application/octet-stream','Content-Length':str(len(body)),'X-Narada-Transfer-Version':'1','X-Narada-Sha256':hashlib.sha256(body).hexdigest(),**extra}
        self.peer.connection.request('GET' if data is None else 'PUT',f'/api/v1/transfers/chunks/{tid}/{index}',body=body,headers=headers)
        r=self.peer.connection.getresponse();raw=r.read();return (r.status,raw) if r.getheader('Content-Type')=='application/octet-stream' else (r.status,json.loads(raw))
    def propose(self,data=None,approve=True,name='received.bin',full_hash=None):
        if data is None:data=self.data
        m,hashes=manifest(data,name,full_hash);tid=str(uuid.uuid4());r=self.ok('open_receive',transfer_id=tid,destination={'root_id':self.root,'directory_id':None,'name':name},manifest=m);self.assertEqual(r['state'],'awaiting_approval')
        if approve:
            self.a.call('transfer_control',{'transfer_id':tid,'target':'receive','action':'accept','name':None})
            for offset in range(0,max(1,len(hashes)),32):self.ok('manifest_page',transfer_id=tid,offset=offset,hashes=hashes[offset:offset+32])
        return tid,m,hashes,data
    def upload(self,tid,data):
        for i in range(0,len(data),CHUNK):self.assertEqual(self.binary(tid,i//CHUNK,data[i:i+CHUNK]),(200,b''))
    def test_independent_peer_downloads_real_bounded_binary_chunks(self):
        self.pair_raw();tid=str(uuid.uuid4());m=self.ok('prepare',transfer_id=tid,source=self.source())['manifest'];page=self.ok('manifest',transfer_id=tid,offset=0);result=b''
        for i,h in enumerate(page['hashes']):status,data=self.binary(tid,i);self.assertEqual(status,200);self.assertLessEqual(len(data),CHUNK);self.assertEqual(hashlib.sha256(data).hexdigest(),h);result+=data
        self.assertEqual(result,self.data);self.assertEqual(hashlib.sha256(result).hexdigest(),m['sha256']);self.assertNotIn(str(self.base),json.dumps(m))
    def test_remote_upload_is_not_accepted_by_create_permission_alone(self):
        self.pair_raw();tid,_,_,data=self.propose(approve=False);self.assertFalse((self.share/'.narada-transfers'/tid).exists());self.assertEqual(self.binary(tid,0,data[:CHUNK])[1]['error']['code'],'RECEIVE_NOT_READY');self.assertFalse((self.share/'received.bin').exists())
    def test_approved_receive_verifies_tail_and_records_publication(self):
        self.pair_raw();tid,m,_,data=self.propose();self.upload(tid,data);receipt=self.ok('commit',transfer_id=tid);self.assertEqual(receipt['sha256'],m['sha256']);self.assertEqual(receipt['state'],'completed');self.assertEqual((self.share/'received.bin').read_bytes(),data);self.assertFalse((self.share/'.narada-transfers'/tid).exists())
    def test_empty_file_still_requires_approval_manifest_and_commit(self):
        self.pair_raw();tid,m,_,_=self.propose(b'');self.assertFalse((self.share/'received.bin').exists());self.assertEqual(self.ok('commit',transfer_id=tid)['sha256'],hashlib.sha256(b'').hexdigest());self.assertEqual((self.share/'received.bin').stat().st_size,0)
    def test_bad_digest_length_index_and_replayed_wrong_data_are_refused(self):
        self.pair_raw();tid,_,_,data=self.propose();self.assertEqual(self.binary(tid,0,b'x'*CHUNK)[1]['error']['code'],'HASH_MISMATCH');self.assertEqual(self.binary(tid,99,b'x')[1]['error']['code'],'INVALID_CHUNK');self.assertEqual(self.binary(tid,0,b'x')[1]['error']['code'],'INVALID_CHUNK');self.assertEqual(self.binary(tid,0,data[:CHUNK]),(200,b''));self.assertEqual(self.binary(tid,0,b'x'*CHUNK)[1]['error']['code'],'HASH_MISMATCH');self.assertEqual(self.ok('status',transfer_id=tid,side='receive')['verified_bytes'],str(CHUNK))
    def test_full_hash_mismatch_blocks_publication_even_when_chunks_match(self):
        self.pair_raw();tid,_,_,data=self.propose(full_hash='a'*64);self.upload(tid,data);self.assertEqual(self.wire('commit',transfer_id=tid)['error']['code'],'HASH_MISMATCH');self.assertFalse((self.share/'received.bin').exists());self.assertTrue((self.share/'payload.bin').exists())
    def test_omitted_and_reordered_manifest_hashes_are_rejected(self):
        self.pair_raw();tid,_,hashes,_=self.propose(approve=False);self.a.call('transfer_control',{'transfer_id':tid,'target':'receive','action':'accept','name':None});self.assertEqual(self.wire('manifest_page',transfer_id=tid,offset=0,hashes=list(reversed(hashes)))['error']['code'],'HASH_MISMATCH');self.assertFalse((self.share/'received.bin').exists())
    def test_duplicate_commit_has_one_durable_outcome(self):
        self.pair_raw();tid,_,_,data=self.propose();self.upload(tid,data);first=self.ok('commit',transfer_id=tid);self.assertEqual(first,self.ok('commit',transfer_id=tid));self.assertEqual((self.share/'received.bin').read_bytes(),data)
    def test_collision_and_explicit_rename_never_overwrite_existing_content(self):
        self.pair_raw();tid,_,_,data=self.propose();self.upload(tid,data);(self.share/'received.bin').write_bytes(b'EXISTING_SENTINEL');self.assertEqual(self.wire('commit',transfer_id=tid)['error']['code'],'NAME_COLLISION');self.a.call('transfer_control',{'transfer_id':tid,'target':'receive','action':'retry','name':'reviewed.bin'});self.assertEqual(self.ok('commit',transfer_id=tid)['name'],'reviewed.bin');self.assertEqual((self.share/'received.bin').read_bytes(),b'EXISTING_SENTINEL');self.assertEqual((self.share/'reviewed.bin').read_bytes(),data)
    def test_permission_withdrawal_blocks_commit_and_new_chunks(self):
        self.pair_raw();tid,_,_,data=self.propose();self.upload(tid,data);peer=self.a.snapshot()['peers'][0];self.a.call('update_peer_permissions',{'peer_id':peer['peer_id'],'expected_revision':peer['grant_revision'],'permissions':{'chat':False,'attachment':False,'roots':[{'root_id':self.root,'capabilities':['browse']}]}});self.reconnect();self.assertEqual(self.wire('commit',transfer_id=tid)['error']['code'],'ACCESS_DENIED');self.assertFalse((self.share/'received.bin').exists())
    def test_source_exclusion_is_checked_after_snapshot_preparation(self):
        self.pair_raw();tid=str(uuid.uuid4());self.ok('prepare',transfer_id=tid,source=self.source());self.a.call('update_root_policy',{'root_id':self.root,'expected_revision':1,'exclusions':['payload.bin']});self.reconnect();self.assertEqual(self.binary(tid,0)[1]['error']['code'],'ACCESS_DENIED')
    def test_over_limit_sparse_source_is_not_snapshotted(self):
        with (self.share/'payload.bin').open('wb') as f:f.truncate(64*1024*1024+1)
        self.a.call('reindex_root',{'root_id':self.root});self.pair_raw();self.assertEqual(self.wire('prepare',transfer_id=str(uuid.uuid4()),source=self.source())['error']['code'],'TRANSFER_LIMIT');self.assertFalse((self.a.path/'transfer-snapshots').exists())
    def test_cancel_after_publication_preserves_the_user_file(self):
        self.pair_raw();tid,_,_,data=self.propose();self.upload(tid,data);receipt=self.ok('commit',transfer_id=tid);self.ok('cancel',transfer_id=tid,side='receive');self.a.call('cleanup_transfers');self.assertEqual((self.share/'received.bin').read_bytes(),data);self.assertEqual(self.ok('status',transfer_id=tid,side='receive')['result'],receipt)
    def test_cancelled_staging_cleanup_preserves_unknown_and_original_files(self):
        self.pair_raw();tid,_,_,data=self.propose();self.assertEqual(self.binary(tid,0,data[:CHUNK]),(200,b''));unknown=self.share/'.narada-transfers'/'do-not-adopt';unknown.write_bytes(b'KEEP');self.ok('cancel',transfer_id=tid,side='receive');result=self.a.call('cleanup_transfers');self.assertGreaterEqual(result['cleaned'],1);self.assertTrue(unknown.exists());self.assertEqual((self.share/'payload.bin').read_bytes(),self.data);self.assertFalse((self.share/'.narada-transfers'/tid).exists())
    def test_restart_rehashes_bits_and_resumes_only_missing_or_uncertain_chunks(self):
        self.pair_raw();tid,m,_,data=self.propose();self.assertEqual(self.binary(tid,0,data[:CHUNK]),(200,b''));self.assertEqual(self.binary(tid,1,data[CHUNK:2*CHUNK]),(200,b''));self.a.stop(kill=True)
        with (self.share/'.narada-transfers'/tid).open('r+b') as f:f.write(b'!');f.flush();os.fsync(f.fileno())
        self.a.start();self.reconnect();status=self.ok('status',transfer_id=tid,side='receive');self.assertEqual(status['missing'],[0,2]);self.assertEqual(status['verified_bytes'],str(CHUNK))
        for index in status['missing']:self.assertEqual(self.binary(tid,index,data[index*CHUNK:(index+1)*CHUNK]),(200,b''))
        self.assertEqual(self.ok('commit',transfer_id=tid)['sha256'],m['sha256']);self.assertEqual((self.share/'received.bin').read_bytes(),data)
    def test_publication_receipt_survives_restart_and_acknowledgement_discard(self):
        self.pair_raw();tid,_,_,data=self.propose();self.upload(tid,data);first=self.ok('commit',transfer_id=tid);self.a.restart(kill=True);self.reconnect();self.assertEqual(first,self.ok('commit',transfer_id=tid));self.assertEqual((self.share/'received.bin').read_bytes(),data)
    def test_unapplied_commit_intent_is_not_blindly_published_at_restart(self):
        self.pair_raw();tid,_,_,data=self.propose();self.upload(tid,data);self.a.stop(kill=True)
        with sqlite3.connect(self.a.path/'narada.db') as db:db.execute("UPDATE transfer_receives SET state='committing',intent_identity=stage_identity WHERE transfer_id=?",(tid,))
        self.a.start();self.reconnect();status=self.ok('status',transfer_id=tid,side='receive');self.assertEqual(status['state'],'blocked');self.assertEqual(status['error_code'],'COMMIT_NOT_APPLIED');self.assertFalse((self.share/'received.bin').exists())
    def test_unrecognized_staging_directory_is_preserved_not_adopted(self):
        self.pair_raw();tid,_,_,_=self.propose(approve=False);stage=self.share/'.narada-transfers';stage.mkdir();(stage/'sentinel').write_bytes(b'KEEP');r=self.a.raw('transfer_control',{'transfer_id':tid,'target':'receive','action':'accept','name':None});self.assertFalse(r['ok']);self.assertEqual((stage/'sentinel').read_bytes(),b'KEEP')
    def test_control_json_budget_is_not_enlarged_for_file_data(self):
        self.pair_raw();r=self.peer.post('/api/v1/transfers',{'hello':p2.HELLO,'version':1,'action':{'kind':'offers','data':'x'*17000}});self.assertEqual(r['error']['code'],'BODY_TOO_LARGE');self.reconnect();r=self.peer.post('/api/v1/transfers',{'hello':p2.HELLO,'version':0,'action':{'kind':'offers'}});self.assertEqual(r['error']['code'],'PROTOCOL_INCOMPATIBLE')
    def test_same_transfer_id_cannot_change_destination_or_actor_intent(self):
        self.pair_raw();tid,m,_,_=self.propose(approve=False);self.assertEqual(self.wire('open_receive',transfer_id=tid,destination={'root_id':self.root,'directory_id':None,'name':'rebound.bin'},manifest=m)['error']['code'],'IDEMPOTENCY_CONFLICT')
    def agents(self,caps=None):
        self.b=p2.Agent(self.base/'client-profile');self.dest=self.base/'client-approved';self.dest.mkdir();(self.dest/'payload.bin').write_bytes(self.data);self.broot=self.b.call('register_root',{'path':str(self.dest),'label':'Client folder','exclusions':[]})['root_id'];self.a.enable();self.b.enable();self.b.call('join_pairing',{'code':self.a.invite()['code']});p2.wait_for(lambda:((self.a.snapshot().get('invitation') or {}).get('pending') or {}).get('proof_verified'));pending=self.a.snapshot()['invitation']['pending'];self.a.call('approve_pairing',{'attempt_id':pending['attempt_id'],'permissions':{'chat':False,'attachment':False,'roots':[{'root_id':self.root,'capabilities':caps or ['browse','read','create']}]}});p2.wait_for(lambda:any(p['state']=='active' and p['connection_state']=='online' for p in self.b.snapshot()['peers']));self.host=self.b.snapshot()['peers'][0]['peer_id'];self.client=self.a.snapshot()['peers'][0]['peer_id']
    def job(self,tid):return next(j for j in self.b.call('list_transfers')['jobs'] if j['transfer_id']==tid)
    def start_job(self,direction='download',name='downloaded.bin'):
        tid=str(uuid.uuid4());source=self.source() if direction=='download' else self.source(self.b,self.broot);destination={'root_id':self.broot if direction=='download' else self.root,'directory_id':None,'name':name};self.b.call('start_transfer',{'transfer_id':tid,'peer_id':self.host,'direction':direction,'source':source,'destination':destination});return tid
    def test_two_real_agents_download_pause_resume_and_verify_publication(self):
        self.data=bytes(range(251))*14000;(self.share/'payload.bin').write_bytes(self.data);self.a.call('reindex_root',{'root_id':self.root});self.agents();tid=self.start_job();p2.wait_for(lambda:int(self.job(tid)['verified_bytes'])>0,timeout=20);self.b.call('transfer_control',{'transfer_id':tid,'target':'job','action':'pause','name':None});before=self.job(tid)['verified_bytes'];time.sleep(.45);self.assertEqual(self.job(tid)['verified_bytes'],before);self.assertEqual(self.job(tid)['state'],'paused');self.assertFalse((self.dest/'downloaded.bin').exists());self.b.call('transfer_control',{'transfer_id':tid,'target':'job','action':'resume','name':None});p2.wait_for(lambda:self.job(tid)['state']=='completed',timeout=25);self.assertEqual((self.dest/'downloaded.bin').read_bytes(),self.data);self.assertEqual(self.job(tid)['sha256'],hashlib.sha256(self.data).hexdigest())
    def test_two_real_agents_upload_waits_for_receiver_approval(self):
        self.agents();tid=self.start_job('upload','uploaded.bin');p2.wait_for(lambda:self.job(tid)['state']=='awaiting_approval',timeout=20);self.assertFalse((self.share/'uploaded.bin').exists());self.a.call('transfer_control',{'transfer_id':tid,'target':'receive','action':'accept','name':None});p2.wait_for(lambda:self.job(tid)['state']=='completed',timeout=25);self.assertEqual((self.share/'uploaded.bin').read_bytes(),self.data)
    def test_inbound_host_offer_is_collected_over_recipient_saved_endpoint(self):
        self.agents();tid=str(uuid.uuid4());self.assertIsNone(self.a.snapshot()['peers'][0]['endpoint']);self.a.call('create_transfer_offer',{'transfer_id':tid,'peer_id':self.client,'source':self.source()});self.b.call('poll_transfer_offers');p2.wait_for(lambda:any(o['transfer_id']==tid for o in self.b.call('list_transfers')['offers']),timeout=20);self.b.call('accept_transfer_offer',{'transfer_id':tid,'peer_id':self.host,'destination':{'root_id':self.broot,'directory_id':None,'name':'pulled.bin'}});p2.wait_for(lambda:self.job(tid)['state']=='completed',timeout=25);self.assertEqual((self.dest/'pulled.bin').read_bytes(),self.data)
    def test_concurrent_jobs_share_bounded_workers_without_control_starvation(self):
        self.agents();one=self.start_job(name='one.bin');two=self.start_job(name='two.bin');started=time.monotonic();self.assertTrue(self.b.call('get_snapshot')['agent']);self.assertLess(time.monotonic()-started,2);p2.wait_for(lambda:all(self.job(i)['state']=='completed' for i in [one,two]),timeout=30);self.assertEqual((self.dest/'one.bin').read_bytes(),self.data);self.assertEqual((self.dest/'two.bin').read_bytes(),self.data)

if __name__=='__main__':unittest.main(verbosity=2)
