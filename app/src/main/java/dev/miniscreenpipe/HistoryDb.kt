package dev.miniscreenpipe

import android.content.Context
import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.graphics.Bitmap
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.ByteArrayOutputStream

data class Entry(val id: Long, val time: Long, val app: String, val pkg: String, val text: String, val image: String, val interval: Int,
    val lastSeen: Long = time, val samples: Int = 1, val fingerprint: String = "", val source: String = "ocr", val quality: String = "unknown") {
    fun json() = JSONObject().put("id", id).put("time", time).put("last_seen", lastSeen).put("samples", samples).put("app", app)
        .put("package", pkg).put("text", text).put("sample_seconds", interval).put("text_source", source).put("ocr_quality", quality).put("image",image)
}
data class HistorySummary(val count: Int, val samples: Int, val appSeconds: List<Pair<String, Long>>)
class HistoryDb private constructor(context: Context, name: String) : SQLiteOpenHelper(context, name, null, 2) {
    private val prefs=Prefs(context)
    val images = File(context.filesDir, if(name == "history.db") "screens" else "test-screens-$name").apply { mkdirs() }
    init { setWriteAheadLoggingEnabled(true) }
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("PRAGMA auto_vacuum=INCREMENTAL")
        db.execSQL("CREATE TABLE captures(id INTEGER PRIMARY KEY, time INTEGER NOT NULL, app TEXT NOT NULL, package TEXT NOT NULL, text TEXT NOT NULL, image TEXT NOT NULL, interval INTEGER NOT NULL, last_seen INTEGER NOT NULL, samples INTEGER NOT NULL DEFAULT 1, fingerprint TEXT NOT NULL DEFAULT '', source TEXT NOT NULL DEFAULT 'ocr', quality TEXT NOT NULL DEFAULT 'unknown', image_bytes INTEGER NOT NULL DEFAULT 0)")
        createIndexes(db)
    }
    override fun onUpgrade(db: SQLiteDatabase, old: Int, next: Int) {
        if(old < 2) {
            db.execSQL("ALTER TABLE captures ADD COLUMN last_seen INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE captures ADD COLUMN samples INTEGER NOT NULL DEFAULT 1")
            db.execSQL("ALTER TABLE captures ADD COLUMN fingerprint TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE captures ADD COLUMN source TEXT NOT NULL DEFAULT 'ocr'")
            db.execSQL("ALTER TABLE captures ADD COLUMN quality TEXT NOT NULL DEFAULT 'unknown'")
            db.execSQL("ALTER TABLE captures ADD COLUMN image_bytes INTEGER NOT NULL DEFAULT 0")
            db.execSQL("UPDATE captures SET last_seen=time")
            db.rawQuery("SELECT id,image FROM captures", null).use { c -> while(c.moveToNext()) db.execSQL("UPDATE captures SET image_bytes=? WHERE id=?", arrayOf(File(images,c.getString(1)).length(),c.getLong(0))) }
            createIndexes(db)
        }
    }
    private fun createIndexes(db: SQLiteDatabase) {
        db.execSQL("CREATE INDEX IF NOT EXISTS time_index ON captures(time)")
        db.execSQL("CREATE INDEX IF NOT EXISTS end_index ON captures(last_seen)")
        db.execSQL("CREATE INDEX IF NOT EXISTS image_index ON captures(image)")
        db.execSQL("CREATE VIRTUAL TABLE capture_search USING fts4(app, package, text, content='captures', tokenize=unicode61)")
        db.execSQL("CREATE TRIGGER search_ai AFTER INSERT ON captures BEGIN INSERT INTO capture_search(docid,app,package,text) VALUES(new.id,new.app,new.package,new.text); END")
        db.execSQL("CREATE TRIGGER search_bd BEFORE DELETE ON captures BEGIN DELETE FROM capture_search WHERE docid=old.id; END")
        db.execSQL("INSERT INTO capture_search(capture_search) VALUES('rebuild')")
    }
    private fun entry(c: android.database.Cursor) = Entry(c.getLong(0),c.getLong(1),c.getString(2),c.getString(3),c.getString(4),c.getString(5),c.getInt(6),c.getLong(7),c.getInt(8),c.getString(9),c.getString(10),c.getString(11))
    private var mergeAllowed = false
    @Synchronized fun breakSession() { mergeAllowed = false }
    @Synchronized fun add(time: Long, app: String, pkg: String, text: String, bitmap: Bitmap, interval: Int,
        fingerprint: String = "", source: String = "ocr", quality: String = "unknown"): Long {
        val normalized = EvidencePolicy.normalize(text)
        val previous = latest()
        if(mergeAllowed && previous != null && previous.image.isNotBlank() && EvidencePolicy.canMerge(previous,time,pkg,fingerprint,normalized,interval)) {
            writableDatabase.execSQL("UPDATE captures SET last_seen=?, samples=samples+1, interval=interval+? WHERE id=?", arrayOf(time, minOf(interval, maxOf(1, ((time-previous.lastSeen)/1000).toInt())), previous.id))
            return previous.id
        }
        val bytes=ImageStorage.encode(bitmap,prefs.resolution,prefs.grayscale)
        val name = "${EvidencePolicy.hash(bytes)}.webp"
        val file = File(images,name)
        val newFile = !file.exists()
        try {
            if(newFile) { val temp=File(images,"$name.tmp"); try { temp.writeBytes(bytes); check(temp.renameTo(file)) } finally { temp.delete() } }
            val values=ContentValues().apply { put("time",time); put("last_seen",time); put("app",app); put("package",pkg); put("text",normalized); put("image",name); put("interval",interval); put("samples",1); put("fingerprint",fingerprint); put("source",source); put("quality",quality); put("image_bytes",file.length()) }
            val id=writableDatabase.insertOrThrow("captures",null,values); mergeAllowed=true; return id
        } catch(e:Exception) { if(newFile) file.delete(); throw e }
    }
    @Synchronized fun latest(): Entry? = readableDatabase.rawQuery("SELECT * FROM captures ORDER BY time DESC,id DESC LIMIT 1",null).use { if(it.moveToFirst()) entry(it) else null }
    @Synchronized fun getEntry(id:Long):Entry? = readableDatabase.rawQuery("SELECT * FROM captures WHERE id=?",arrayOf(id.toString())).use { if(it.moveToFirst())entry(it) else null }
    private fun filter(query:String,start:Long,end:Long): Pair<String,Array<String>> {
        val args=mutableListOf(start.toString(),end.toString())
        var where="last_seen >= ? AND time < ?"
        if(query.isNotBlank()) { val match=EvidencePolicy.ftsQuery(query); if(match.isBlank()) where += " AND 0" else { where += " AND id IN (SELECT docid FROM capture_search WHERE capture_search MATCH ?)"; args.add(match) } }
        return where to args.toTypedArray()
    }
    @Synchronized fun search(query:String,start:Long,end:Long,limit:Int=250):List<Entry> {
        val (where,args)=filter(query,start,end)
        return readableDatabase.query("captures",null,where,args,null,null,"time DESC,id DESC",if(limit>0)limit.toString() else null).use { c -> buildList { while(c.moveToNext()) add(entry(c)) } }
    }
    @Synchronized fun summary(query:String,start:Long,end:Long):HistorySummary {
        val (where,args)=filter(query,start,end)
        val counts=readableDatabase.rawQuery("SELECT COUNT(*),COALESCE(SUM(samples),0) FROM captures WHERE $where",args).use { it.moveToFirst(); it.getInt(0) to it.getInt(1) }
        val apps=readableDatabase.rawQuery("SELECT app,SUM(interval) FROM captures WHERE $where GROUP BY app ORDER BY SUM(interval) DESC LIMIT 5",args).use { c -> buildList { while(c.moveToNext()) add(c.getString(0) to c.getLong(1)) } }
        return HistorySummary(counts.first,counts.second,apps)
    }
    data class Retrieved(val entries:List<Entry>,val note:String)
    @Synchronized fun retrieve(query:String,start:Long,end:Long,question:String):Retrieved {
        val plan=RetrievalPolicy.plan(question,start,end)
        if(plan.start>=plan.end)return Retrieved(emptyList(),plan.scope)
        val (where,args)=filter(query,plan.start,plan.end)
        val candidates=linkedMapOf<Long,Entry>()
        var matched=0
        if(!plan.overview) {
            data class Hit(val id:Long,val time:Long,val score:Double)
            val order=compareBy<Hit> { it.score }.thenBy { it.time }.thenBy { it.id }
            val heap=java.util.PriorityQueue(order)
            val match=EvidencePolicy.ftsQuery(plan.words,or=true)
            // No newest-N cutoff: evaluate all matching index statistics while retaining only a small heap.
            readableDatabase.rawQuery("SELECT captures.id,captures.time,matchinfo(capture_search,'pcnalx') FROM capture_search JOIN captures ON captures.id=capture_search.docid WHERE capture_search MATCH ? AND $where",arrayOf(match)+args).use { c ->
                while(c.moveToNext()) {
                    if(Thread.currentThread().isInterrupted)throw InterruptedException("Retrieval cancelled")
                    matched++
                    val hit=Hit(c.getLong(0),c.getLong(1),RetrievalPolicy.bm25(c.getBlob(2)))
                    if(heap.size<RetrievalPolicy.MAX_CANDIDATES)heap.add(hit)
                    else if(order.compare(hit,heap.peek())>0) { heap.poll();heap.add(hit) }
                }
            }
            val hits=heap.toList().sortedWith(order.reversed())
            if(hits.isNotEmpty()) {
                val ids=hits.map { it.id }
                readableDatabase.rawQuery("SELECT * FROM captures WHERE id IN (${ids.joinToString(",") { "?" }})",ids.map(Long::toString).toTypedArray()).use { c -> while(c.moveToNext()) { val e=entry(c);candidates[e.id]=e } }
                val ranked=hits.mapNotNull { candidates[it.id] }
                return Retrieved(RetrievalPolicy.diverse(ranked,RetrievalPolicy.MAX_FOCUSED),"Local BM25: $matched matching moments; at most ${RetrievalPolicy.MAX_FOCUSED} distinct excerpts selected. ${plan.scope}.")
            }
            return Retrieved(emptyList(),"No indexed topic matches. ${plan.scope}.")
        }
        // Overview requests sample indexed time buckets without loading every history ID or full OCR document.
        val bounds=readableDatabase.rawQuery("SELECT MIN(time),MAX(time),COUNT(*) FROM captures WHERE $where",args).use { c -> c.moveToFirst();Triple(c.getLong(0),c.getLong(1),c.getInt(2)) }
        if(bounds.third==0)return Retrieved(emptyList(),plan.scope)
        val bins=RetrievalPolicy.MAX_OVERVIEW-2
        val width=maxOf(1,(bounds.second-bounds.first)/bins+1)
        repeat(bins) { i ->
            val lo=bounds.first+i*width;val hi=lo+width
            readableDatabase.rawQuery("SELECT * FROM captures WHERE $where AND time>=? AND time<? ORDER BY time,id LIMIT 1",args+arrayOf(lo.toString(),hi.toString())).use { c -> if(c.moveToFirst()) { val e=entry(c);candidates[e.id]=e } }
        }
        for(direction in listOf("ASC","DESC"))readableDatabase.rawQuery("SELECT * FROM captures WHERE $where ORDER BY time $direction,id $direction LIMIT 1",args).use { c -> if(c.moveToFirst()) { val e=entry(c);candidates[e.id]=e } }
        return Retrieved(candidates.values.sortedBy { it.time }.take(RetrievalPolicy.MAX_OVERVIEW),"Time-distributed overview sampled from ${bounds.third} moments; counts of retrieved excerpts are not total app usage. ${plan.scope}.")
    }
    @Synchronized fun evidence(query:String,start:Long,end:Long,question:String)=retrieve(query,start,end,question).entries
    @Synchronized fun delete(id:Long) {
        val db=writableDatabase
        val image=db.rawQuery("SELECT image FROM captures WHERE id=?",arrayOf(id.toString())).use { if(it.moveToFirst())it.getString(0) else null } ?: return
        db.delete("captures","id=?",arrayOf(id.toString()))
        val used=db.rawQuery("SELECT 1 FROM captures WHERE image=? LIMIT 1",arrayOf(image)).use { it.moveToFirst() }
        if(image.isNotBlank() && !used) File(images,image).delete()
        mergeAllowed=false
    }
    @Synchronized fun clear() { writableDatabase.delete("captures",null,null); images.listFiles()?.forEach { it.delete() }; mergeAllowed=false }
    @Synchronized fun imageBytes():Long = readableDatabase.rawQuery("SELECT COALESCE(SUM(bytes),0) FROM (SELECT MAX(image_bytes) bytes FROM captures GROUP BY image)",null).use { it.moveToFirst();it.getLong(0) }
    @Synchronized fun prune(days:Int,maxImageBytes:Long=500L*1024*1024) {
        val cutoff=System.currentTimeMillis()-days*86400000L
        val expired=readableDatabase.rawQuery("SELECT id FROM captures WHERE last_seen<?",arrayOf(cutoff.toString())).use { c -> buildList { while(c.moveToNext())add(c.getLong(0)) } }
        if(days>0) expired.forEach(::delete)
        // Evict oldest screenshot assets, retaining searchable text and its evidence IDs.
        var bytes=imageBytes()
        if(bytes>maxImageBytes) {
            val oldest=readableDatabase.rawQuery("SELECT image,MAX(image_bytes) FROM captures GROUP BY image ORDER BY MAX(last_seen)",null).use { c -> buildList { while(c.moveToNext())add(c.getString(0) to c.getLong(1)) } }
            for((image,size) in oldest) { if(bytes<=maxImageBytes)break; if(image.isBlank())continue; writableDatabase.execSQL("UPDATE captures SET image='',image_bytes=0 WHERE image=?",arrayOf(image)); File(images,image).delete();bytes-=size }
        }
        val live=readableDatabase.rawQuery("SELECT DISTINCT image FROM captures",null).use { c -> buildSet { while(c.moveToNext())add(c.getString(0)) } }
        images.listFiles()?.filter { it.name !in live }?.forEach { it.delete() }
        writableDatabase.execSQL("PRAGMA incremental_vacuum(128)")
        writableDatabase.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)",null).use { it.moveToFirst() }
    }
    @Synchronized fun export(query:String,start:Long,end:Long):String = JSONObject().put("format","mini-screenpipe-v2")
        .put("captures",JSONArray().apply { search(query,start,end,0).forEach { put(it.json()) } }).toString(2)
    @Synchronized fun writeExport(output:java.io.OutputStream,query:String,start:Long,end:Long) {
        val (where,args)=filter(query,start,end)
        val writer=output.bufferedWriter(Charsets.UTF_8)
        writer.write("{\"format\":\"mini-screenpipe-v2\",\"captures\":[")
        readableDatabase.query("captures",null,where,args,null,null,"time DESC,id DESC").use { cursor ->
            var first=true
            while(cursor.moveToNext()) { if(!first)writer.write(",");first=false;writer.write(entry(cursor).json().toString()) }
        }
        writer.write("]}");writer.flush() // ZIP owns the stream; closing this writer would close the archive.
    }
    @Synchronized fun exportZip(destination:File) {
        java.util.zip.ZipOutputStream(destination.outputStream().buffered()).use { zip ->
            fun put(name:String,write:(java.io.OutputStream)->Unit) { zip.putNextEntry(java.util.zip.ZipEntry(name));write(zip);zip.closeEntry() }
            put("history.json") { writeExport(it,"",0,Long.MAX_VALUE) }
            val files=readableDatabase.rawQuery("SELECT DISTINCT image FROM captures WHERE image<>''",null).use { c -> buildList { while(c.moveToNext())add(c.getString(0)) } }
            files.forEach { name -> val file=File(images,name);if(file.isFile)put("screenshots/$name") { out -> file.inputStream().use { it.copyTo(out) } } }
        }
    }
    companion object {
        @Volatile private var instance:HistoryDb?=null
        fun get(context:Context):HistoryDb=instance ?: synchronized(this) { instance ?: HistoryDb(context.applicationContext,"history.db").also { instance=it } }
        fun isolated(context:Context,name:String)=HistoryDb(context.applicationContext,name)
    }
}
