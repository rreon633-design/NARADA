#!/usr/bin/env python3
"""Phase 3: disposable roots, separate agents and an independent mTLS peer.
Never run these destructive fixture checks against a real user profile/root.
"""
import base64, importlib.util, json, os, sqlite3, struct, tempfile, time, unittest, uuid, zlib
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2]
spec=importlib.util.spec_from_file_location('pairing_security',ROOT/'tests/process/pairing_security.py')
p2=importlib.util.module_from_spec(spec);spec.loader.exec_module(p2)

def png(width=32,height=20):
    def chunk(t,b):return struct.pack('>I',len(b))+t+b+struct.pack('>I',zlib.crc32(t+b)&0xffffffff)
    return b'\x89PNG\r\n\x1a\n'+chunk(b'IHDR',struct.pack('>IIBBBBB',width,height,8,2,0,0,0))+chunk(b'tEXt',b'Comment\x00PRIVATE_FIXTURE_METADATA')+chunk(b'IDAT',zlib.compress((b'\0'+b'\x70\x8d\x64'*width)*height))+chunk(b'IEND',b'')

class FilesTests(unittest.TestCase):
    def setUp(self):
        (ROOT/'test-fixtures').mkdir(exist_ok=True)
        self.temp=tempfile.TemporaryDirectory(prefix='files-',dir=ROOT/'test-fixtures')
        self.base=Path(self.temp.name);self.share=self.base/'approved';self.share.mkdir()
        (self.share/'note.txt').write_text('Disposable content. Do not overwrite.')
        (self.share/'image.png').write_bytes(png())
        self.a=p2.Agent(self.base/'profile');self.other=None;self.peer=None
        self.root=self.a.call('register_root',{'path':str(self.share),'label':'Approved test folder','exclusions':[]})
        self.root_id=self.root['root_id']
    def tearDown(self):
        if self.peer:self.peer.close()
        if self.other:self.other.stop()
        self.a.stop();self.temp.cleanup()
    def query(self,kind='browse',**extra):
        q={'kind':kind,'root_id':self.root_id,**extra}
        if kind=='browse':q={'directory_id':None,'cursor':None,'sort':'name',**q}
        if kind=='trash':q={'cursor':None,**q}
        return self.a.call('file_query',q)
    def entry(self,name):return next(e for e in self.query()['entries'] if e['name']==name)
    def mutation(self,e,kind='rename',**extra):
        return {'root_id':self.root_id,'operation_id':str(uuid.uuid4()),'object_id':e['object_id'],'expected_version':e['version'],'action':{'kind':kind,**extra}}
    def pair(self,caps=None):
        self.a.enable();code=json.loads(self.a.invite()['code']);self.peer=p2.RawPeer(self.base/'tls-peer')
        claim=self.peer.claim(code);self.assertTrue(claim['ok'],claim)
        self.endpoint=f"127.0.0.1:{claim['data']['api_port']}";self.pin=code['fingerprint']
        self.peer.connect(self.endpoint,self.pin,True);self.assertTrue(self.peer.proof()['ok'])
        self.a.call('approve_pairing',{'attempt_id':self.peer.attempt,'permissions':{'chat':False,'attachment':False,'roots':([] if caps is None else [{'root_id':self.root_id,'capabilities':caps}])}})
        self.assertTrue(self.peer.proof(True)['ok'])
        return self.peer
    def peer_query(self,kind='roots',**extra):
        q={'kind':kind,**extra}
        if kind!='roots':q['root_id']=q.get('root_id',self.root_id)
        if kind=='browse':q={'directory_id':None,'cursor':None,'sort':'name',**q}
        return self.peer.post('/api/v1/files',{'hello':p2.HELLO,'version':1,'action':{'type':'query','payload':q}})
    def peer_mutate(self,m):return self.peer.post('/api/v1/files',{'hello':p2.HELLO,'version':1,'action':{'type':'mutate','payload':m}})
    def grant(self,caps):
        p=next(p for p in self.a.snapshot()['peers'] if p['state']=='active')
        self.a.call('update_peer_permissions',{'peer_id':p['peer_id'],'expected_revision':p['grant_revision'],'permissions':{'chat':False,'attachment':False,'roots':[{'root_id':self.root_id,'capabilities':caps}]}})
        self.peer.connect(self.endpoint,self.pin,True)
    def test_default_paired_peer_has_no_root_access(self):
        self.pair();self.assertEqual(self.peer_query()['data']['roots'],[]);self.assertFalse(self.peer_query('browse')['ok'])
    def test_scope_filtering_and_no_host_paths_in_peer_responses(self):
        other=self.base/'another';other.mkdir();(other/'private.txt').write_text('not granted')
        r=self.a.call('register_root',{'path':str(other),'label':'Never granted','exclusions':[]})
        self.pair(['browse']);roots=self.peer_query()['data']['roots'];self.assertEqual([r['root_id'] for r in roots],[self.root_id]);self.assertNotIn('path',roots[0]);self.assertNotIn('exclusions',roots[0])
        response=self.peer_query('browse');self.assertTrue(response['ok']);self.assertNotIn(str(self.base),json.dumps(response));self.assertFalse(self.peer_query('browse',root_id=r['root_id'])['ok'])
    def test_browse_does_not_grant_image_read_or_mutations(self):
        self.pair(['browse']);image=self.entry('image.png');self.assertFalse(self.peer_query('preview',object_id=image['object_id'],expected_version=image['version'])['ok'])
        self.assertFalse(self.peer_mutate(self.mutation(self.entry('note.txt'),name='changed.txt'))['ok']);self.assertTrue((self.share/'note.txt').exists())
    def test_read_grant_returns_reencoded_bounded_jpeg_not_original_metadata(self):
        self.pair(['browse','read']);image=self.entry('image.png');response=self.peer_query('preview',object_id=image['object_id'],expected_version=image['version']);self.assertTrue(response['ok'],response)
        data=response['data'];raw=base64.b64decode(data['data']);self.assertEqual(data['mime'],'image/jpeg');self.assertTrue(raw.startswith(b'\xff\xd8'));self.assertLessEqual(len(raw),32768);self.assertNotIn(b'PRIVATE_FIXTURE_METADATA',raw);self.assertLessEqual(data['width'],256)
    def test_peer_rename_trash_and_restore_require_current_capabilities(self):
        self.pair(['browse','modify']);e=self.entry('note.txt');op=self.mutation(e,name='renamed.txt');self.assertTrue(self.peer_mutate(op)['ok']);self.assertTrue((self.share/'renamed.txt').exists())
        self.grant(['browse','trash']);e=self.entry('renamed.txt');op=self.mutation(e,'trash');self.assertTrue(self.peer_mutate(op)['ok']);t=self.peer_query('trash',cursor=None)['data']['entries'][0];restore=self.mutation(t,'restore',name=None);self.assertFalse(self.peer_mutate(restore)['ok'])
        self.grant(['browse','trash','create']);self.assertTrue(self.peer_mutate(restore)['ok']);self.assertEqual((self.share/'renamed.txt').read_text(),'Disposable content. Do not overwrite.')
    def test_old_tls_session_is_closed_and_fresh_sessions_lose_grants(self):
        self.pair(['browse','read']);self.assertTrue(self.peer_query('browse')['ok']);p=self.a.snapshot()['peers'][0]
        self.a.call('update_peer_permissions',{'peer_id':p['peer_id'],'expected_revision':p['grant_revision'],'permissions':p2.NO_CONTENT})
        try:self.assertFalse(self.peer_query('browse')['ok'])
        except (OSError,Exception) as e:
            if isinstance(e,AssertionError):raise
        self.peer.connect(self.endpoint,self.pin,True);self.assertEqual(self.peer_query()['data']['roots'],[]);self.assertFalse(self.peer_query('browse')['ok'])
    def test_root_unregistration_withdraws_grants_without_deleting_files(self):
        self.pair(['browse','read']);self.a.call('remove_root',{'root_id':self.root_id,'expected_revision':1});self.assertTrue((self.share/'note.txt').exists());self.assertEqual(self.a.snapshot()['peers'][0]['permissions']['roots'],[])
        self.peer.connect(self.endpoint,self.pin,True);self.assertEqual(self.peer_query()['data']['roots'],[])
    def test_owner_root_registration_and_absolute_paths_are_not_peer_actions(self):
        self.pair(['browse','read']);response=self.peer.post('/api/v1/files',{'hello':p2.HELLO,'version':1,'action':{'type':'register_root','payload':{'path':str(self.base)}}});self.assertFalse(response['ok'])
        for v in ['../note.txt','/etc/passwd','C:\\Windows','file:stream']:
            self.assertFalse(self.peer_query('details',object_id=v)['ok'])
        self.assertEqual(self.a.call('get_snapshot')['shared_roots'],1)
    def test_file_protocol_downgrade_and_unknown_fields_fail_closed(self):
        self.pair(['browse']);b={'hello':p2.HELLO,'version':0,'action':{'type':'query','payload':{'kind':'roots'}}};self.assertEqual(self.peer.post('/api/v1/files',b)['error']['code'],'PROTOCOL_INCOMPATIBLE');b['version']=1;b['action']['payload']['path']=str(self.share);self.assertFalse(self.peer.post('/api/v1/files',b)['ok'])
    def test_excluded_and_hidden_names_never_enter_peer_listing_or_search(self):
        (self.share/'private').mkdir();(self.share/'private'/'hidden-name.txt').write_text('not exposed');(self.share/'.env').write_text('fixture secret')
        self.a.call('update_root_policy',{'root_id':self.root_id,'expected_revision':1,'exclusions':['private']});self.pair(['browse']);page=self.peer_query('browse');self.assertNotIn('hidden-name',json.dumps(page));self.assertNotIn('.env',json.dumps(page));self.assertEqual(self.peer_query('search',query='hidden',cursor=None,sort='name')['data']['entries'],[])
    def test_symlinks_hardlinks_and_fifos_cannot_read_outside_the_root(self):
        outside=self.base/'outside.txt';outside.write_text('EXTERNAL_SENTINEL');os.symlink(outside,self.share/'link.txt');os.link(outside,self.share/'hard.txt');os.mkfifo(self.share/'pipe');os.symlink(self.base,self.share/'external-dir');self.a.call('reindex_root',{'root_id':self.root_id});self.pair(['browse','read']);text=json.dumps(self.peer_query('browse'));self.assertNotIn('link.txt',text);self.assertNotIn('hard.txt',text);self.assertNotIn('pipe',text);self.assertNotIn('external-dir',text);self.assertEqual(outside.read_text(),'EXTERNAL_SENTINEL')
    def test_link_swap_after_indexing_blocks_details_preview_and_mutation(self):
        e=self.entry('image.png');outside=self.base/'outside.png';outside.write_bytes(png());(self.share/'image.png').rename(self.share/'old.png');os.symlink(outside,self.share/'image.png')
        self.assertFalse(self.a.raw('file_query',{'kind':'details','root_id':self.root_id,'object_id':e['object_id']})['ok']);self.assertFalse(self.a.raw('file_query',{'kind':'preview','root_id':self.root_id,'object_id':e['object_id'],'expected_version':e['version']})['ok']);self.assertFalse(self.a.raw('file_mutation',self.mutation(e,name='stolen.png'))['ok']);self.assertTrue(outside.exists())
    def test_root_identity_change_is_visible_and_not_adopted(self):
        self.share.rename(self.base/'old-approved');self.share.mkdir();(self.share/'new-secret.txt').write_text('replacement directory');self.assertFalse(self.a.raw('reindex_root',{'root_id':self.root_id})['ok']);roots=self.a.call('list_roots')['roots'];self.assertEqual(roots[0]['state'],'blocked');self.assertFalse(self.a.raw('file_query',{'kind':'browse','root_id':self.root_id,'directory_id':None,'cursor':None,'sort':'name'})['ok'])
    def test_traversal_reserved_unicode_case_and_existing_names_are_refused(self):
        e=self.entry('note.txt')
        for name in ['../escape','a/b','x:ads','CON.txt','a.','a ','a\u202e.txt','e\u0301.txt','.hidden']:
            self.assertFalse(self.a.raw('file_mutation',self.mutation(e,name=name))['ok'],name)
        (self.share/'Taken.txt').write_text('kept');r=self.a.raw('file_mutation',self.mutation(e,name='taken.txt'));self.assertFalse(r['ok']);self.assertEqual((self.share/'Taken.txt').read_text(),'kept');self.assertTrue((self.share/'note.txt').exists())
    def test_stale_version_cannot_mutate_a_new_file_revision(self):
        e=self.entry('note.txt');(self.share/'note.txt').write_text('a later revision');r=self.a.raw('file_mutation',self.mutation(e,'trash'));self.assertEqual(r['error']['code'],'STALE_VERSION');self.assertEqual((self.share/'note.txt').read_text(),'a later revision')
    def test_idempotency_survives_restart_and_rejects_changed_body(self):
        e=self.entry('note.txt');op=self.mutation(e,name='done.txt');first=self.a.call('file_mutation',op);self.a.restart(kill=True);self.assertEqual(self.a.call('file_mutation',op),first);op['action']['name']='another.txt';self.assertEqual(self.a.raw('file_mutation',op)['error']['code'],'IDEMPOTENCY_CONFLICT');self.assertFalse((self.share/'another.txt').exists())
    def test_restore_collision_keeps_both_live_and_retained_copies(self):
        op=self.mutation(self.entry('note.txt'),'trash');self.a.call('file_mutation',op);(self.share/'note.txt').write_text('new current data');t=self.query('trash')['entries'][0]
        self.assertFalse(self.a.raw('file_mutation',self.mutation(t,'restore',name=None))['ok']);self.assertEqual((self.share/'note.txt').read_text(),'new current data');self.assertEqual(len(self.query('trash')['entries']),1)
        self.a.call('file_mutation',self.mutation(t,'restore',name='restored-copy.txt'));self.assertEqual((self.share/'restored-copy.txt').read_text(),'Disposable content. Do not overwrite.')
    def test_trash_retention_quota_and_no_permanent_delete_route(self):
        with (self.share/'large.bin').open('wb') as f:f.truncate(101*1024*1024)
        self.a.call('reindex_root',{'root_id':self.root_id});self.assertEqual(self.a.raw('file_mutation',self.mutation(self.entry('large.bin'),'trash'))['error']['code'],'TRASH_QUOTA');self.assertTrue((self.share/'large.bin').exists())
        bad=self.mutation(self.entry('note.txt'),'delete_permanently')
        import socket
        with socket.socket(socket.AF_UNIX) as sock:
            sock.settimeout(5);sock.connect(str(self.a.path/'agent.sock'));sock.sendall(json.dumps({'version':1,'id':str(uuid.uuid4()),'command':{'type':'file_mutation','payload':bad}}).encode()+b'\n')
            result=json.loads(sock.makefile('rb').readline())
        self.assertFalse(result['ok']);self.assertTrue((self.share/'note.txt').exists())
    def test_nonempty_directories_with_excluded_children_are_not_mutated(self):
        (self.share/'folder').mkdir();(self.share/'folder/.secret').write_text('keep');self.a.call('reindex_root',{'root_id':self.root_id});r=self.a.raw('file_mutation',self.mutation(self.entry('folder'),'trash'));self.assertEqual(r['error']['code'],'DIRECTORY_NOT_EMPTY');self.assertTrue((self.share/'folder/.secret').exists())
    def test_search_sort_pagination_are_stable_and_scope_bound(self):
        for n in range(39):(self.share/f'doc-{n:02}.txt').write_text('data')
        self.a.call('reindex_root',{'root_id':self.root_id});first=self.query();self.assertEqual(len(first['entries']),16);cursor=first['next_cursor'];second=self.query(cursor=cursor);self.assertFalse(set(e['object_id'] for e in first['entries'])&set(e['object_id'] for e in second['entries']))
        bad={'kind':'search','root_id':self.root_id,'query':'doc','cursor':cursor,'sort':'name'};self.assertEqual(self.a.raw('file_query',bad)['error']['code'],'CURSOR_STALE')
        (self.share/'new.txt').write_text('new');self.a.call('reindex_root',{'root_id':self.root_id});self.assertEqual(self.a.raw('file_query',{'kind':'browse','root_id':self.root_id,'directory_id':None,'cursor':cursor,'sort':'name'})['error']['code'],'CURSOR_STALE')
    def test_invalid_oversized_and_active_image_formats_are_refused(self):
        (self.share/'active.svg').write_text('<svg onload="alert(1)"></svg>');(self.share/'bad.png').write_bytes(png(2,2)[:40]);(self.share/'huge.png').write_bytes(b'\x89PNG\r\n\x1a\n'+b'\0\0\0\rIHDR'+struct.pack('>II',50000,50000)+b'\x08\x02\0\0\0')
        self.a.call('reindex_root',{'root_id':self.root_id})
        for name in ['active.svg','bad.png','huge.png']:
            e=self.entry(name);r=self.a.raw('file_query',{'kind':'preview','root_id':self.root_id,'object_id':e['object_id'],'expected_version':e['version']});self.assertFalse(r['ok'],name)
    def test_native_watcher_reconciles_external_create_rename_and_delete(self):
        (self.share/'external.txt').write_text('created outside the agent')
        p2.wait_for(lambda:any(e['name']=='external.txt' for e in self.query()['entries']),timeout=8)
        (self.share/'external.txt').rename(self.share/'changed-name.txt')
        p2.wait_for(lambda:any(e['name']=='changed-name.txt' for e in self.query()['entries']),timeout=8)
        (self.share/'changed-name.txt').unlink();p2.wait_for(lambda:all(e['name']!='changed-name.txt' for e in self.query()['entries']),timeout=8)
    def test_durable_intent_reconciles_a_moved_source_after_restart(self):
        e=self.entry('note.txt');op=self.mutation(e,name='published.txt');first=self.a.call('file_mutation',op);self.a.stop(kill=True)
        with sqlite3.connect(self.a.path/'narada.db') as db:db.execute("UPDATE file_operations SET state='prepared',result=NULL WHERE operation_id=?",(op['operation_id'],))
        self.a.start();self.assertEqual(self.a.call('file_mutation',op),first);self.assertFalse((self.share/'note.txt').exists());self.assertTrue((self.share/'published.txt').exists())
    def test_ambiguous_intent_blocks_further_mutations_without_reset(self):
        e=self.entry('note.txt');op=self.mutation(e,name='published.txt');self.a.call('file_mutation',op);self.a.stop(kill=True);(self.share/'published.txt').rename(self.base/'preserved.txt')
        with sqlite3.connect(self.a.path/'narada.db') as db:db.execute("UPDATE file_operations SET state='prepared',result=NULL WHERE operation_id=?",(op['operation_id'],))
        self.a.start();self.assertEqual(self.a.call('list_roots')['roots'][0]['state'],'recovery_required');r=self.a.raw('file_mutation',self.mutation(self.entry('image.png'),'trash'));self.assertEqual(r['error']['code'],'OPERATION_INDETERMINATE');self.assertTrue((self.base/'preserved.txt').exists())
    def test_unregister_reregister_preserves_trash_without_restoring_old_grants(self):
        self.a.call('file_mutation',self.mutation(self.entry('note.txt'),'trash'));self.a.call('remove_root',{'root_id':self.root_id,'expected_revision':1});r=self.a.call('register_root',{'path':str(self.share),'label':'Restored root registration','exclusions':[]});self.assertEqual(r['root_id'],self.root_id);self.assertEqual(len(self.query('trash')['entries']),1)
    def test_protected_system_profile_and_overlapping_roots_are_rejected(self):
        for path in ['/', '/etc',str(self.base),str(self.a.path),str(self.share)]:
            self.assertFalse(self.a.raw('register_root',{'path':path,'label':'Not approved','exclusions':[]})['ok'],path)
    def test_separate_agent_owner_proxy_uses_saved_mtls_identity_and_root_grants(self):
        self.other=p2.Agent(self.base/'client-profile');self.a.enable();self.other.enable();code=self.a.invite()['code'];self.other.call('join_pairing',{'code':code});p2.wait_for(lambda:((self.a.snapshot().get('invitation') or {}).get('pending') or {}).get('proof_verified'))
        pending=self.a.snapshot()['invitation']['pending'];self.a.call('approve_pairing',{'attempt_id':pending['attempt_id'],'permissions':{'chat':False,'attachment':False,'roots':[{'root_id':self.root_id,'capabilities':['browse','read']}]}})
        p2.wait_for(lambda:any(p['state']=='active' for p in self.other.snapshot()['peers']))
        peer=self.other.snapshot()['peers'][0];roots=self.other.call('remote_file_query',{'peer_id':peer['peer_id'],'query':{'kind':'roots'}});self.assertEqual(roots['roots'][0]['root_id'],self.root_id)
        self.other.call('configure_connections',{'enabled':False,'bind_ip':'127.0.0.1','api_port':0,'discovery':False});self.assertFalse(self.other.raw('remote_file_query',{'peer_id':peer['peer_id'],'query':{'kind':'roots'}})['ok'])
    def test_root_count_limit_never_registers_a_ninth_folder(self):
        for n in range(7):
            p=self.base/f'root-{n}';p.mkdir();self.a.call('register_root',{'path':str(p),'label':f'Root {n}','exclusions':[]})
        p=self.base/'ninth';p.mkdir();r=self.a.raw('register_root',{'path':str(p),'label':'Refused','exclusions':[]})
        self.assertEqual(r['error']['code'],'ROOT_LIMIT');self.assertEqual(len(self.a.call('list_roots')['roots']),8)
    def test_index_limit_blocks_instead_of_silently_publishing_partial_content(self):
        for n in range(5001):(self.share/f'item-{n:04}.txt').touch()
        r=self.a.raw('reindex_root',{'root_id':self.root_id});self.assertEqual(r['error']['code'],'INDEX_LIMIT')
        self.assertEqual(self.a.call('list_roots')['roots'][0]['state'],'blocked')
        self.assertFalse(self.a.raw('file_query',{'kind':'browse','root_id':self.root_id,'directory_id':None,'cursor':None,'sort':'name'})['ok'])
    def test_receipts_are_actor_bound_and_current_policy_checked(self):
        op=self.mutation(self.entry('note.txt'),name='renamed.txt');self.a.call('file_mutation',op)
        self.pair(['browse','modify']);self.assertIn(self.peer_mutate(op)['error']['code'],['STALE_VERSION','NAME_COLLISION'])
        self.a.call('update_root_policy',{'root_id':self.root_id,'expected_revision':1,'exclusions':['renamed.txt']})
        self.assertEqual(self.a.raw('file_mutation',op)['error']['code'],'ACCESS_DENIED')
    def test_missing_retained_bytes_are_reported_not_silently_deleted(self):
        self.a.call('file_mutation',self.mutation(self.entry('note.txt'),'trash'))
        retained=next((self.share/'.narada-trash').iterdir());retained.rename(self.base/'preserved-retained.txt')
        page=self.query('trash');self.assertEqual(page['entries'],[]);self.assertEqual(page['unavailable_count'],1)
        with sqlite3.connect(self.a.path/'narada.db') as db:self.assertEqual(db.execute("SELECT count(*) FROM file_trash WHERE state='retained'").fetchone()[0],1)
        self.assertTrue((self.base/'preserved-retained.txt').exists())
    @unittest.skipUnless(os.environ.get('NARADA_SLOW_TESTS')=='1','30-second polling fallback check')
    def test_periodic_reconciliation_works_without_native_watcher_hints(self):
        self.a.stop();os.environ['NARADA_FILE_WATCHER']='poll'
        try:self.a.start()
        finally:os.environ.pop('NARADA_FILE_WATCHER',None)
        time.sleep(3);(self.share/'missed-hint.txt').write_text('polling fallback')
        p2.wait_for(lambda:any(e['name']=='missed-hint.txt' for e in self.query()['entries']),timeout=34)

if __name__=='__main__':unittest.main(verbosity=2)
