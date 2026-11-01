package com.narada.connection

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FileBrowserTest {
    private val authority=FileAuthority("a".repeat(64),FileSamples.cursor,1,1,1,1)
    private class Gateway : FilesGateway {
        val queries=mutableListOf<FileQuery>();var cancelCount=0;var mutationCount=0
        var queryResult:suspend (FileQuery)->FileResult={ q -> when(q) {
            FileQuery.Roots -> FileResult.Roots(RootsReply(listOf(FileSamples.root),1))
            is FileQuery.Details -> FileResult.Details(FileSamples.entry)
            is FileQuery.Preview -> FileResult.Preview(FileSamples.preview().pixels(q))
            is FileQuery.Trash -> FileResult.Trash(TrashReply(FileSamples.root,listOf(FileSamples.trash),0,null,"Retained"))
            else -> FileResult.Page(FileSamples.page)
        } }
        override suspend fun query(query:FileQuery):FileResult { queries+=query;return queryResult(query) }
        override suspend fun mutate(root:String,objectId:String,version:String,action:FileAction,confirmed:Boolean):FileOperation {
            demand(confirmed,"LOCAL_CONFIRMATION_REQUIRED");mutationCount++;val input=FileSamples.input(action.kind,action.name)
            return FileOperation("a".repeat(64),FileSamples.cursor,input,1,FileOperationState.COMMITTED,FileSamples.receipt(input))
        }
        override suspend fun retry(operationId:String,confirmed:Boolean)=error("Not used")
        override suspend fun dismiss(operationId:String,acknowledged:Boolean) { demand(acknowledged,"LOCAL_CONFIRMATION_REQUIRED") }
        override fun cancel(){cancelCount++}
    }
    private fun TestScope.start(g:Gateway=Gateway()):Pair<Gateway,FileBrowser> {
        val browser=FileBrowser(g,backgroundScope);browser.authorityChanged(authority,emptyList());browser.visible(true);runCurrent();return g to browser
    }
    @Test fun offlineScreenDoesNotFabricateRootsOrSendTraffic()=runTest {
        val g=Gateway();val b=FileBrowser(g,backgroundScope);b.visible(true);runCurrent();b.refresh();runCurrent()
        assertTrue(g.queries.isEmpty());assertTrue(b.state.value.roots.isEmpty());assertEquals("FILES_OFFLINE",b.state.value.error)
    }
    @Test fun grantedRootsAndDirectoryNavigationUseOpaqueIds()=runTest {
        val(g,b)=start();b.openRoot(FileSamples.rootId);runCurrent();b.openDirectory(FileSamples.directoryId);runCurrent()
        assertEquals(FileQuery.Browse(FileSamples.rootId,FileSamples.directoryId),g.queries.last());assertEquals(FileSamples.directoryId,b.state.value.location!!.directoryId)
    }
    @Test fun unlistedRootAndObjectCannotBeSelected()=runTest {
        val(g,b)=start();b.openRoot("../../");runCurrent();assertEquals(1,g.queries.size);assertEquals("ACCESS_DENIED",b.state.value.error)
        b.openRoot(FileSamples.rootId);runCurrent();b.select("content://secret");runCurrent();assertNull(b.state.value.selection);assertEquals(2,g.queries.size)
    }
    @Test fun searchIsExplicitAndRootScopedAndSortRestartsPaging()=runTest {
        val(g,b)=start();b.openRoot(FileSamples.rootId);runCurrent();b.search("photo");runCurrent()
        assertEquals(FileQuery.Search(FileSamples.rootId,"photo"),g.queries.last());b.sort(FileSort.SIZE);runCurrent()
        assertEquals(FileQuery.Search(FileSamples.rootId,"photo",sort=FileSort.SIZE),g.queries.last());assertEquals(1,b.state.value.pageNumber)
    }
    @Test fun badSearchDoesNotIssueRequest()=runTest {
        val(g,b)=start();b.openRoot(FileSamples.rootId);runCurrent();val count=g.queries.size;b.search("x".repeat(129));runCurrent();assertEquals(count,g.queries.size);assertEquals("INVALID_FILE_SEARCH",b.state.value.error)
    }
    @Test fun nextPageReplacesRowsAndPreviousUsesSavedOpaqueCursor()=runTest {
        val(g,b)=start();g.queryResult={q -> FileResult.Page(if((q as FileQuery.Browse).cursor==null)FileSamples.page.copy(next_cursor=FileSamples.cursor) else FileSamples.page.copy(entries=listOf(FileSamples.entry))) }
        b.openRoot(FileSamples.rootId);runCurrent();b.next();runCurrent();assertEquals(1,b.state.value.page!!.entries.size);assertEquals(2,b.state.value.pageNumber);assertTrue(b.state.value.canPrevious)
        b.previous();runCurrent();assertEquals(1,b.state.value.pageNumber);assertEquals(2,b.state.value.page!!.entries.size)
    }
    @Test fun indexEpochChangeNeverMergesDifferentSnapshots()=runTest {
        val(g,b)=start();g.queryResult={FileResult.Page(FileSamples.page.copy(next_cursor=FileSamples.cursor))};b.openRoot(FileSamples.rootId);runCurrent()
        g.queryResult={FileResult.Page(FileSamples.page.copy(root=FileSamples.root.copy(index_epoch="2"),index_epoch="2"))};b.next();runCurrent()
        assertNull(b.state.value.page);assertEquals("CURSOR_STALE",b.state.value.error);assertFalse(b.state.value.canPrevious)
    }
    @Test fun repeatedCursorIsRejectedWithoutAnInfiniteLoop()=runTest {
        val(g,b)=start();g.queryResult={FileResult.Page(FileSamples.page.copy(next_cursor=FileSamples.cursor))};b.openRoot(FileSamples.rootId);runCurrent();b.next();runCurrent()
        assertNull(b.state.value.page);assertEquals("CURSOR_STALE",b.state.value.error);val count=g.queries.size;b.next();runCurrent();assertEquals(count,g.queries.size)
    }
    @Test fun currentPermissionChangeClearsRowsSelectionAndPixels()=runTest {
        val(_,b)=start();b.openRoot(FileSamples.rootId);runCurrent();b.select(FileSamples.objectId);runCurrent();b.preview();runCurrent();assertNotNull(b.state.value.preview)
        b.authorityChanged(authority.copy(remoteRevision=2),emptyList());runCurrent()
        assertTrue(b.state.value.roots.isEmpty());assertNull(b.state.value.page);assertNull(b.state.value.selection);assertNull(b.state.value.preview)
    }
    @Test fun backgroundOrLeavingTabDropsAllContentWithoutPersistingNavigation()=runTest {
        val(_,b)=start();b.openRoot(FileSamples.rootId);runCurrent();b.authorityChanged(null,emptyList(),false);runCurrent();assertNull(b.state.value.location);assertTrue(b.state.value.roots.isEmpty())
        b.visible(false);assertFalse(b.state.value.visible);assertTrue(b.state.value.operations.isEmpty())
    }
    @Test fun cancellationUncooperativeGatewayCannotPublishLateMetadata()=runTest {
        val(g,b)=start();g.queryResult={withContext(NonCancellable){delay(500)};FileResult.Page(FileSamples.page)}
        b.openRoot(FileSamples.rootId);runCurrent();b.authorityChanged(null,emptyList(),false);advanceTimeBy(501);runCurrent()
        assertNull(b.state.value.page);assertNull(b.state.value.location);assertFalse(b.state.value.authorized)
    }
    @Test fun detailsRevalidateSelectedVersionInsteadOfMutatingStaleListEntry()=runTest {
        val(g,b)=start();b.openRoot(FileSamples.rootId);runCurrent();g.queryResult={FileResult.Details(FileSamples.entry.copy(version="b".repeat(64)))}
        b.select(FileSamples.objectId);runCurrent();assertNull(b.state.value.selection);assertEquals("STALE_VERSION",b.state.value.error)
    }
    @Test fun previewIsRemovedOnSelectionClose()=runTest {
        val(_,b)=start();b.openRoot(FileSamples.rootId);runCurrent();b.select(FileSamples.objectId);runCurrent();b.preview();runCurrent();assertNotNull(b.state.value.preview)
        b.closeSelection();assertNull(b.state.value.preview);assertNull(b.state.value.selection)
    }
    @Test fun confirmationCannotActOnAnotherOrStaleSelection()=runTest {
        val(g,b)=start();b.openRoot(FileSamples.rootId);runCurrent();b.select(FileSamples.objectId);runCurrent()
        b.mutate(FileAction("rename","changed.jpg"),FileSamples.directoryId,FileSamples.version,true);runCurrent();assertEquals(0,g.mutationCount)
        b.mutate(FileAction("rename","changed.jpg"),FileSamples.objectId,FileSamples.version,false);runCurrent();assertEquals(0,g.mutationCount)
    }
    @Test fun successfulMutationDoesNotOptimisticallyPatchAFileListing()=runTest {
        val(g,b)=start();b.openRoot(FileSamples.rootId);runCurrent();b.select(FileSamples.objectId);runCurrent()
        b.mutate(FileAction("rename","changed.jpg"),FileSamples.objectId,FileSamples.version,true);runCurrent();assertEquals(1,g.mutationCount)
        assertNull(b.state.value.page);assertNull(b.state.value.selection);assertTrue(b.state.value.notice!!.contains("receipt was saved"))
    }
    @Test fun metadataExpiresRatherThanBecomingAnOfflineCache()=runTest {
        val(_,b)=start();assertFalse(b.state.value.roots.isEmpty());advanceTimeBy(60_001);runCurrent()
        assertTrue(b.state.value.roots.isEmpty());assertNull(b.state.value.page);assertTrue(b.state.value.notice!!.contains("expired"))
    }
    @Test fun permissionFailureIsNotAnEmptySuccessfulDirectory()=runTest {
        val(g,b)=start();g.queryResult={fail("ACCESS_DENIED")};b.openRoot(FileSamples.rootId);runCurrent();assertNull(b.state.value.page);assertTrue(b.state.value.roots.isEmpty());assertEquals("ACCESS_DENIED",b.state.value.error)
    }
    @Test fun localDismissalWorksOfflineOnlyWithExplicitAcknowledgement()=runTest {
        val g=Gateway();val b=FileBrowser(g,backgroundScope);b.visible(true);b.dismiss(FileSamples.cursor,false);runCurrent();assertEquals("LOCAL_CONFIRMATION_REQUIRED",b.state.value.error)
        b.dismiss(FileSamples.cursor,true);runCurrent();assertTrue(b.state.value.notice!!.contains("does not cancel"));assertTrue(g.queries.isEmpty())
    }
    @Test fun closingSelectionDoesNotCancelTheOriginalMetadataExpiry()=runTest {
        val(_,b)=start();b.openRoot(FileSamples.rootId);runCurrent();b.select(FileSamples.objectId);runCurrent()
        advanceTimeBy(30_000);b.closeSelection();assertNotNull(b.state.value.page)
        advanceTimeBy(30_001);runCurrent();assertNull(b.state.value.page);assertTrue(b.state.value.roots.isEmpty())
    }

    @Test fun downloadChoiceBindsThePeerPairingAndExactLiveSelection()=runTest {
        val(_,b)=start();b.openRoot(FileSamples.rootId);runCurrent();b.select(FileSamples.objectId);runCurrent()
        val choice=b.downloadChoice(FileSamples.objectId,FileSamples.version)
        assertEquals(authority.peerId,choice.peerId);assertEquals(authority.attemptId,choice.pairingAttempt)
        assertEquals(TransferSource(FileSamples.rootId,FileSamples.objectId,FileSamples.version),choice.source);assertEquals(1024L,choice.size)
    }
    @Test fun downloadChoiceCannotReuseASelectionAfterAuthorityChange()=runTest {
        val(_,b)=start();b.openRoot(FileSamples.rootId);runCurrent();b.select(FileSamples.objectId);runCurrent()
        b.authorityChanged(authority.copy(peerId="b".repeat(64)),emptyList())
        expectFailure("FILE_SELECTION_STALE"){b.downloadChoice(FileSamples.objectId,FileSamples.version)}
    }
    @Test fun downloadChoiceRejectsWrongSelectedIdentifierAndVersion()=runTest {
        val(_,b)=start();b.openRoot(FileSamples.rootId);runCurrent();b.select(FileSamples.objectId);runCurrent()
        expectFailure("FILE_SELECTION_STALE"){b.downloadChoice(FileSamples.directoryId,FileSamples.version)}
        expectFailure("FILE_SELECTION_STALE"){b.downloadChoice(FileSamples.objectId,"b".repeat(64))}
    }
    @Test fun downloadChoiceStillRequiresReadNotJustBrowse()=runTest {
        val(g,b)=start();g.queryResult={q->if(q is FileQuery.Details)FileResult.Details(FileSamples.entry)else FileResult.Page(FileSamples.page.copy(root=FileSamples.root.copy(capabilities=listOf("browse"))))}
        b.openRoot(FileSamples.rootId);runCurrent();b.select(FileSamples.objectId);runCurrent()
        expectFailure("ACCESS_DENIED"){b.downloadChoice(FileSamples.objectId,FileSamples.version)}
    }

}
