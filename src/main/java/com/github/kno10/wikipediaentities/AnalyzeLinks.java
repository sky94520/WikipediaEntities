package com.github.kno10.wikipediaentities;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.file.FileSystems;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

import org.apache.lucene.document.Document;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.PhraseQuery;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.store.FSDirectory;

import com.github.kno10.wikipediaentities.util.CounterSet;
import com.github.kno10.wikipediaentities.util.CounterSet.Entry;
import com.github.kno10.wikipediaentities.util.Progress;
import com.github.kno10.wikipediaentities.util.Unique;
import com.github.kno10.wikipediaentities.util.Util;

import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import it.unimi.dsi.fastutil.objects.Reference2ReferenceOpenHashMap;

public class AnalyzeLinks {
  private static final int MINIMUM_MENTIONS = 20;

  /** Collect unique strings. */
  Unique<String> unique = new Unique<>();

  Progress prog = new Progress("Computing support");

  BlockingQueue<Candidate> proqueue = new ArrayBlockingQueue<>(1000);

  BlockingQueue<Candidate> outqueue = new ArrayBlockingQueue<>(1000 + 1);

  boolean shutdown = false;

  /** Lucene index searcher */
  IndexSearcher searcher;

  private void run() throws IOException {
    //最多允许的线程数量
    int par = Math.min(Integer.valueOf(Config.get("parallelism")), Runtime.getRuntime().availableProcessors());
    if(par < 1) {
      throw new Error("At least 1 consumer must be allowed!");
    }

    // String unification, for performance.
    Unique<String> unique = new Unique<>(50_000_000);
    // Load Wikidata information:
    Map<String, String> datamap = loadWikidata(unique, Config.get("wikidata.output"));
    //Map<String, String> datamap = loadWikidata(unique, "wiki-2021/wikidata1.tsv.gz");
    System.out.format("Read %d wikidata maps.\n", datamap.size());
    // Load redirects
    Reference2ReferenceOpenHashMap<String, String> redmap = loadRedirects(unique, Config.get("redirects.output"));
    System.out.format("Read %d redirects.\n", redmap.size());

    computeClosure(datamap, redmap);
    System.out.format("computed redirect clouse of %d wikidata maps.\n", datamap.size());
    redmap = null; // Free.

    String nam = Config.get("linktext.output");
    String dir = Config.get("indexer.dir");
    String out = Config.get("entities.output");
    FSDirectory ldir = FSDirectory.open(FileSystems.getDefault().getPath(dir));
    IndexReader reader = DirectoryReader.open(ldir);
    searcher = new IndexSearcher(reader);
    //添加一个输出线程，若干个工作线程
    ArrayList<Thread> threads = new ArrayList<>();
    threads.add(new OutputThread(out));
    for(int i = 0; i < par; i++)
      threads.add(new WorkerThread("Worker-" + i, datamap));

    // Start all:
    for(Thread th : threads)
      th.start();
    readall(nam);
    // Wait for all:
    for(Thread th : threads) {
      try {
        th.join();
        // Help the writer thread to shutdown...
        synchronized(monitor) {
          monitor.notify();
        }
      }
      catch(InterruptedException e) {
        e.printStackTrace();
      }
    }
  }

  /**
   * Load wikidata information, i.e. a map WikiDataID to language versions, and
   * return a map language version to WikiDataID.
   *
   * @param unique String unifier
   * @param fnam File name
   * @return Map language version to wiki data id.
   * @throws IOException
   */
  private Map<String, String> loadWikidata(Unique<String> unique, String fnam) throws IOException {
    Map<String, String> m = new Object2ObjectOpenHashMap<>(30_000_000);
    try (BufferedReader r = new BufferedReader(//
    new InputStreamReader(Util.openInput(fnam)))) {
      String line = r.readLine();
      String[] header = line.split("\t");
      StringBuilder buf = new StringBuilder();
      while((line = r.readLine()) != null) {
        String[] cols = line.split("\t");
        assert (cols.length == header.length);
        for(int i = 1; i < cols.length; i++) {
          final String title = cols[i];
          if(title == null || title.length() == 0)
            continue;
        }
        String nam = null;
        for(int i = 1; i < cols.length; i++) {
          if(cols[i] == null || cols[i].length() == 0) {
            continue;
          }
          if(nam == null) {
            buf.setLength(0);
            nam = unique.addOrGet(buf.append(cols[0]).append(':').append(cols[i].replace(':', ' ')).toString());
          }
          buf.setLength(0);
          buf.append(header[i]).append(':').append(cols[i]);
          String prev = m.put(unique.addOrGet(buf.toString()), nam);
          assert (prev == null);
        }
      }
    }
    return m;
  }

  /**
   * Load the redirects data. Note: we perform string unification, and use a
   * Reference-based HashMap for performance reasons.
   *
   * @param unique String unification
   * @param fnam File name
   * @return Hash map of redirects
   * @throws IOException
   */
  private Reference2ReferenceOpenHashMap<String, String> loadRedirects(Unique<String> unique, String fnam) throws IOException {
    Reference2ReferenceOpenHashMap<String, String> m = new Reference2ReferenceOpenHashMap<>(15_000_000);
    try (BufferedReader r = new BufferedReader(//
    new InputStreamReader(Util.openInput(fnam)))) {
      String line = null;
      while((line = r.readLine()) != null) {
        String[] cols = line.split("\t");
        assert (cols.length == 2);
        m.put(unique.addOrGet(cols[0]), unique.addOrGet(cols[1]));
      }
    }
    return m;
  }

  /**
   * Compute the transitive closure of redirects, to be able to quickly follow a
   * redirect chain to the final WikiData entry.
   *
   * @param datamap Wikidata map (will be modified)
   * @param redmap Redirection map (read-only)
   */
  private void computeClosure(Map<String, String> datamap, Reference2ReferenceOpenHashMap<String, String> redmap) {
    System.err.println("Computing transitive closure of redirects.");
    ObjectOpenHashSet<String> seen = new ObjectOpenHashSet<>();
    // Iterate using a copy to avoid concurrent modification
    int i = 0;
    int total_size = redmap.size();
    for(ObjectIterator<Reference2ReferenceOpenHashMap.Entry<String, String>> it = redmap.reference2ReferenceEntrySet().fastIterator(); it.hasNext();) {
      if(++i % 1000 == 0) {
        System.out.format("Computing closure progress: %d/%d\n", i, total_size);
      }
      Reference2ReferenceOpenHashMap.Entry<String, String> ent = it.next();
      //key -> targ
      String key = ent.getKey(), targ = ent.getValue();
      assert (targ != null);
      //key对应的真实词条，让targ->词条
      String next = datamap.get(key);
      if(next != null) {
        //在数据中存在该映射，链式插入短语
        do {
          //旧值；如果给定键不存在任何值，则返回null。
          String oldVal = datamap.put(targ, next);
            if (oldVal == null)
            {
              System.err.format("Warning: WikiData references a redirect: %s > %s > %s\n", next, key, targ);
            }
            //TODO:值相同，跳出
            else if (oldVal.equals(next))
            {
              break;
            }
        }while((targ = redmap.get(targ)) != null);
        continue;
      }
      seen.clear();
      seen.add(key);
      seen.add(targ);
      while(true) {
        next = datamap.get(targ);
        if(next != null) {
          datamap.put(key, next);
          break;
        }
        next = redmap.get(targ);
        if(next == null) {
          break;
        }
        if(!seen.add(next)) {
          System.err.format("Redirect cycle detected involving %s > %s > %s\n", key, targ, next);
          break;
        }
        targ = next;
      }
    }
  }

  /**
   * Class wrapping a candidate.
   *
   * If the candidate has "failed", we {@code null} the query string to indicate
   * this to the output thread.
   */
  static class Candidate {
    String query, matches;

    public Candidate(String query) {
      this.query = query;
      this.matches = null;
    }

    @Override
    public String toString() {
      return query + " " + matches;
    }
  }

  /** Used for awaking the output thread */
  Object monitor = new Object();

  private class WorkerThread extends Thread {
    Object2IntOpenHashMap<String> counters = new Object2IntOpenHashMap<>();

    StringBuilder buf = new StringBuilder();

    Map<String, String> datamap;

    ObjectOpenHashSet<String> dups = new ObjectOpenHashSet<>(),
        dupsExact = new ObjectOpenHashSet<>();

    static final int EXACT = 0x1_0000;

    public WorkerThread(String name, Map<String, String> datamap) {
      super(name);
      this.datamap = datamap;
    }

    @Override
    public void run() {
      while(!proqueue.isEmpty() || !shutdown) {
        try {
          Candidate a = proqueue.poll(100, TimeUnit.MILLISECONDS);
          if(a == null)
            continue;
          analyze(a);
          prog.incrementAndLog();
        }
        catch(InterruptedException e) {
          break;
        }
        catch(IOException e) {
          e.printStackTrace();
          break;
        }
      }
    }

    private void analyze(Candidate cand) throws IOException {
      //TODO: 和lucene相关，查询
      PhraseQuery.Builder pq = new PhraseQuery.Builder();
      //字是按照空格划分的
      for(String t : cand.query.split(" "))
        pq.add(new Term(LuceneWikipediaIndexer.LUCENE_FIELD_TEXT, t));
      //每次查询前都清空原先的数据
      counters.clear();
      // Careful: max count must be less than 64k, because we use short counts!
      //通过字查询文档
      TopDocs res = searcher.search(pq.build(), 0xFFFF);
      ScoreDoc[] docs = res.scoreDocs;
      if(docs.length < MINIMUM_MENTIONS) {
        cand.query = null; // Flag as dead.
        return; // Too rare.
      }
      int minsupp = Math.max(MINIMUM_MENTIONS, docs.length / 10);
      //计算这些文档中的link短语，若有一个短语有对应的datamap，则增加1（每个文档增加1）
      int weight = 0;
      //遍历所有文档 以及对应的所有link
      for(int i = 0; i < docs.length; ++i) {
        Document d = searcher.doc(docs[i].doc);
        //获取游文档对应的自由链接词语
        String[] lis = d.get(LuceneWikipediaIndexer.LUCENE_FIELD_LINKS).split("\t");
        if(lis.length == 0) {
          // String dtitle = d.get(LuceneWikipediaIndexer.LUCENE_FIELD_TITLE);
          // System.err.format("No links for %s.\n", dtitle);
          continue;
        }
        dups.clear();
        dupsExact.clear();
        // Even positions are link targets:
        // Beware: Java "split" loses trailing separators!
        boolean used = false;
        for(int j = 0; j < lis.length; j += 2) {
          final String targ = datamap.get(lis[j]);
          if(targ != null) {
            if(dups.add(targ)) {
              counters.addTo(targ, 1);
              used = true;
            }
            //还有短语 并且这个短语等于查询 并且未曾添加到dupsExact，则为它添加权重65536
            if(j + 1 < lis.length && lis[j + 1].equalsIgnoreCase(cand.query) && dupsExact.add(targ))
              counters.addTo(targ, EXACT);
          }//end if
        }//end for 遍历文档中的link短语
        if(used)
          weight++;
      }//end for 遍历文档
      boolean output = false;
      if(counters.size() > 0) {
        buf.setLength(0); // clear
        //查询语句 总文档数 weight：在wikidata中的不重复关键字数
        buf.append(cand.query);
        buf.append('\t').append(res.totalHits);
        buf.append('\t').append(weight);
        List<Entry<String>> sorted = CounterSet.descending(counters);
        int max = weight;
        // int max = Math.max(docs.length, sorted.get(0).getCombinedCount());
        final double norm = Math.log1p(.1 * max);
        //记录每个短语以及对应出现的次数
        for(CounterSet.Entry<String> c : sorted) {
          final int count = c.getSearchCount();
          if(count < minsupp)
            break;
          if(count >> 1 > minsupp) // Increase cutoff
            minsupp = count >> 1;
          String targ = c.getKey();
          if(targ == null)
            continue; // Was not a candidate.
          int conf = (int) Math.round(Math.log1p(.1 * c.getSearchCount()) / norm * 100.);
          buf.append('\t').append(targ);
          buf.append(':').append(c.getSearchCount());
          buf.append(':').append(c.getExactCount());
          buf.append(':').append(conf).append('%');
          output = true;
        }
      }
      if(output) {
        // System.err.println(buf.toString());
        cand.matches = buf.toString(); // Flag as good.
      }
      else
        cand.query = null; // Flag as dead.
      // Wake up writer thread, if waiting.
      synchronized(monitor) {
        monitor.notifyAll();
      }
    }
  }//end for WorkerThread
  public void readall(String nam) {
    //open linktext.gz
    try (InputStream in = Util.openInput(nam);
        BufferedReader r = new BufferedReader(new InputStreamReader(in))) {
      String line;
      while((line = r.readLine()) != null) {
        if(line.startsWith("category ")) {
          continue; // Artifact.
        }
        try {
          Candidate cand = new Candidate(line);
          proqueue.put(cand);
          outqueue.put(cand);
        }
        catch(InterruptedException e) {
          break;
        }
      }
    }
    catch(IOException e) {
      e.printStackTrace();
    }
    shutdown = true; // Don't wait for more input to arrive.
  }
  //实体输出线程处理类
  private class OutputThread extends Thread {
    private String nam;

    public OutputThread(String nam) {
      super("Output Thread");
      this.nam = nam;
    }

    @Override
    public void run() {
      //定时查询队列，如果获取到，则判断有没有匹配到的数据，有则输出到文件
      try (PrintStream out = Util.openOutput(nam)) {
        while(!outqueue.isEmpty() || !shutdown) {
          try {
            //一定时间从队列取数据，若未取到，返回null
            //检索并除去此队列的头，如果有必要使元素可用，则等待指定的等待时间。
            Candidate a = outqueue.poll(100, TimeUnit.MILLISECONDS);
            if(a == null)
              continue;
            //拿到一个 candidate，若还没查询，则一直等待处理后，才被唤醒
            while(true) {
              if(a.query == null)
                break; // Query failed to yield good results.
              if(a.matches != null) { // Success
                out.append(a.matches);
                out.append('\n');
                break;
              }
              synchronized(monitor) {
                monitor.wait(); // Wait for wakeup signal
              }
            }//end while
          }
          catch(InterruptedException e) {
            break;
          }
        }
      }
      catch(IOException e) {
        e.printStackTrace();
      }
      System.err.println("Output thread has finished!");
    }
  }

  public static void main(String[] args) {
    try {
      (new AnalyzeLinks()).run();
    }
    catch(IOException e) {
      e.printStackTrace();
    }
  }
}
