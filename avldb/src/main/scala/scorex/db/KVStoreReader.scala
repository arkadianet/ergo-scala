package scorex.db

import java.util.concurrent.locks.ReentrantReadWriteLock

import org.iq80.leveldb.{DB, ReadOptions}

import scala.collection.mutable

/** Thrown when a range scan would visit more keys than its `visitBudget`.
  * Callers exposing scans to untrusted input map this to an explicit client
  * error. NEVER catch-and-truncate: a truncated page is indistinguishable from
  * an exhausted range and silently corrupts pagination (spec §5 rev. 3).
  */
final class RangeScanBudgetExceeded(msg: String) extends RuntimeException(msg)

/**
  * Basic interface for reading from LevelDB key-value storage.
  * Both keys and values are var-sized byte arrays.
  */
trait KVStoreReader extends AutoCloseable {

  type K = Array[Byte]
  type V = Array[Byte]

  protected val db: DB

  protected val lock = new ReentrantReadWriteLock()

  /**
    * Read database element by its key
    * @param key - key
    * @return element if exists, None otherwise
    */
  def get(key: K): Option[V] = {
    lock.readLock().lock()
    try {
      Option(db.get(key))
    } finally {
      lock.readLock().unlock()
    }
  }


  /**
    * Iterate through the database to read elements according to a filter function.
    * @param cond - the filter function
    * @return iterator over elements satisfying the filter function
    */
  def getWithFilter(cond: (K, V) => Boolean): Iterator[(K, V)] = {
    val ro = new ReadOptions()
    ro.snapshot(db.getSnapshot)
    val iter = db.iterator(ro)
    try {
      iter.seekToFirst()
      val bf = mutable.ArrayBuffer.empty[(K, V)]
      while (iter.hasNext) {
        val next = iter.next()
        val key = next.getKey
        val value = next.getValue
        if (cond(key, value)) bf += (key -> value)
      }
      bf.toIterator
    } finally {
      iter.close()
      ro.snapshot().close()
    }
  }

  /**
    * Read all the database elements.
    * @return iterator over database contents
    */
  def getAll: Iterator[(K, V)] = getWithFilter((_, _) => true)

  /** Returns value associated with the key, or default value from user
    */
  def getOrElse(key: K, default: => V): V =
    get(key).getOrElse(default)

  /**
    * Batch get.
    *
    * Finds all keys from given iterable.
    * Result is returned in an iterable of key-value pairs.
    * If key is not found, None value is included in a resulting pair.
    *
    *
    * @param keys keys to lookup
    * @return iterable over key-value pairs found in store
    */
  def get(keys: Iterable[K]): Iterable[(K, Option[V])] = {
    val ret = scala.collection.mutable.ArrayBuffer.empty[(K, Option[V])]
    keys.foreach { key =>
      ret += key -> get(key)
    }
    ret
  }

  /**
    * Get keys in range
    * @param start - beginning of the range (inclusive)
    * @param end - end of the range (inclusive)
    * @return
    */
  def getRange(start: K, end: K, limit: Int = Int.MaxValue): Array[(K, V)] = {
    val ro = new ReadOptions()
    ro.snapshot(db.getSnapshot)
    val iter = db.iterator(ro)
    try {
      iter.seek(start)
      val bf = mutable.ArrayBuffer.empty[(K, V)]
      var elemCounter = 0
      while (iter.hasNext && elemCounter < limit) {
        val next = iter.next()
        if(ByteArrayUtils.compare(next.getKey, end) <= 0) {
          elemCounter += 1
          bf += (next.getKey -> next.getValue)
        } else elemCounter = limit // break
      }
      bf.toArray[(K,V)]
    } finally {
      iter.close()
      ro.snapshot().close()
    }
  }

  /**
    * Scan raw keys in [start, end] (inclusive) in either direction.
    *
    * `keyFilter` is applied BEFORE offset/limit accounting; both offset and
    * limit count FILTERED KEYS.
    *
    * IMPLEMENTATION NOTE — forward iteration only. DBIterator declares prev()
    * and seekToLast(), but org.iq80.leveldb.impl.SeekingIteratorAdapter (the
    * pure-Java backend, which LDBFactory selects unconditionally on macOS and as
    * a fallback elsewhere) throws UnsupportedOperationException from all four
    * reverse methods. Descending is therefore served by a forward scan retaining
    * the trailing offset+limit matches in a FIXED CIRCULAR ARRAY: O(range)
    * visits, O(offset+limit) memory, O(1) per visited key. Do not "optimise"
    * this back to prev(), and do not replace the circular array with
    * ArrayBuffer.remove(0), which is O(window) per eviction.
    *
    * Cost: forward is O(offset + limit) visits; reverse is O(range) visits.
    * Callers exposing reverse to untrusted input MUST pass a finite visitBudget.
    */
  def getRangeWithFilter(start: K, end: K, offset: Int, limit: Int, reverse: Boolean,
                         visitBudget: Long = Long.MaxValue)
                        (keyFilter: K => Boolean): Array[(K, V)] = {
    if (limit <= 0 || offset < 0) return Array.empty[(K, V)]
    val window: Long = offset.toLong + limit.toLong
    if (reverse && (window > visitBudget || window > Int.MaxValue)) {
      // A reverse page needing more matches than the budget allows can never
      // complete within it; refuse BEFORE allocating the window array. Also
      // guards the Int cast below against a pathological offset.
      throw new RangeScanBudgetExceeded(
        s"reverse window $window exceeds visit budget $visitBudget")
    }
    lock.readLock().lock()
    val ro = new ReadOptions()
    ro.snapshot(db.getSnapshot)
    val iter = db.iterator(ro)
    try {
      iter.seek(start)
      var visits = 0L
      if (!reverse) {
        val bf = mutable.ArrayBuffer.empty[(K, V)]
        var skipped = 0
        var continue = true
        while (continue && iter.hasNext) {
          val n = iter.next()
          visits += 1
          if (visits > visitBudget) {
            throw new RangeScanBudgetExceeded(s"scan exceeded visit budget $visitBudget")
          }
          if (ByteArrayUtils.compare(n.getKey, end) > 0) {
            continue = false
          } else if (keyFilter(n.getKey)) {
            if (skipped < offset) skipped += 1 else bf += (n.getKey -> n.getValue)
            if (bf.length >= limit) continue = false
          }
        }
        bf.toArray[(K, V)]
      } else {
        val cap = window.toInt
        val buf = new Array[(K, V)](cap)
        var matches = 0L
        var continue = true
        while (continue && iter.hasNext) {
          val n = iter.next()
          visits += 1
          if (visits > visitBudget) {
            throw new RangeScanBudgetExceeded(s"scan exceeded visit budget $visitBudget")
          }
          if (ByteArrayUtils.compare(n.getKey, end) > 0) {
            continue = false
          } else if (keyFilter(n.getKey)) {
            buf((matches % cap).toInt) = (n.getKey, n.getValue)
            matches += 1
          }
        }
        // buf holds the last `held` matches. Match number g was written to slot
        // g % cap and is only overwritten by match g + cap, so every retained
        // match (g >= matches - held) still lives at slot g % cap. The
        // descending page at `offset` is, in match numbers, the interval
        // [base + max(0, hi - limit), base + hi) reversed, where
        // base = matches - held and hi = held - offset.
        val held = math.min(matches, cap.toLong).toInt
        val hi = held - offset
        if (hi <= 0) Array.empty[(K, V)]
        else {
          val lo = math.max(0, hi - limit)
          val base = matches - held
          val out = new Array[(K, V)](hi - lo)
          var j = hi - 1
          var i = 0
          while (j >= lo) {
            out(i) = buf(((base + j) % cap).toInt)
            i += 1
            j -= 1
          }
          out
        }
      }
    } finally {
      iter.close()
      ro.snapshot().close()
      lock.readLock().unlock()
    }
  }

  /**
    * Close the database
    */
  def close(): Unit = {
    lock.writeLock().lock()
    try {
      db.close()
    } finally {
      lock.writeLock().unlock()
    }
  }

}
