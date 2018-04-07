import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.queries.mlt.MoreLikeThis;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.RAMDirectory;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

public class Main {
    private static final String FULLPATH_FIELD = "fullpath";

    private static final String FILENAME_FIELD = "filename";

    private static final String CONTENTS_FIELD = "contents";

    private static void loadData(IndexWriter writer, Directory index, String path, Analyzer analyzer) throws IOException {
        AtomicInteger i = new AtomicInteger(1);
        Files.walk(Paths.get(path))
            .parallel()
            .filter(f -> Files.isRegularFile(f))
            .map(Path::toFile)
            .map(Main::getDocumentFromFile)
            .filter(Objects::nonNull)
            .forEach(doc -> {
                try {
                    writer.addDocument(doc);
                    System.out.printf("\rLoaded doc #%d", i.getAndAdd(1));
                } catch (IOException e) {
                    e.printStackTrace();
                }

            });

        System.out.println("\nDone!");
    }

    private static List<File> getFilesForProfile(String path) throws IOException{
        ArrayList<File> l = new ArrayList<>();
        Files.walk(Paths.get(path))
                .filter(f -> Files.isRegularFile(f))
                .map(Path::toFile)
                .forEach(l::add);
        return l;
    }

    private static IndexWriter createIndex(Directory directory, Analyzer analyzer) throws IOException {
        return new IndexWriter(directory, new IndexWriterConfig(analyzer));
    }

    public static Document getDocumentFromFile(File f) {
        Document doc = new Document();
        try{
            TextField contents = new TextField(CONTENTS_FIELD, new FileReader(f));
            StringField filename = new StringField(FILENAME_FIELD, f.getName(), Field.Store.YES);
            StringField filepath = new StringField(FULLPATH_FIELD, f.getCanonicalPath(), Field.Store.YES);

            doc.add(contents);
            doc.add(filename);
            doc.add(filepath);

        }catch (IOException e){
            return null;
        }
        return doc;

    }

    private static void getRecommendation(Directory directory, String path_profile, StandardAnalyzer analyzer) throws IOException {
        List<File> filesForProfile = getFilesForProfile(path_profile);

        List<FileReader> readersForProfile = filesForProfile.stream()
                .map(file -> {
                        try{
                            return new FileReader(file);
                        }catch (IOException e){
                            e.printStackTrace();
                        }
                        return null;
                }).filter(Objects::nonNull)
                .collect(Collectors.toList()) ;

        FileReader[] readersSpace = new FileReader[readersForProfile.size()];
        IndexReader readerAll = DirectoryReader.open(directory);

        MoreLikeThis mlt = new MoreLikeThis(readerAll);
        mlt.setMinTermFreq(1);
        mlt.setMinWordLen(4);
        mlt.setAnalyzer(analyzer);

        Query likeProfile = mlt.like(CONTENTS_FIELD, readersForProfile.toArray(readersSpace));

        IndexSearcher searcher = new IndexSearcher(readerAll);

        TopDocs topDocs = searcher.search(likeProfile, 100);

        List<ScoreDoc> scoreDocs = List.of(topDocs.scoreDocs);

        //filter the docs that are in the profile from the result
        List<Document> filtered = scoreDocs.stream().map(scoreDoc -> {
            try {
                return searcher.doc(scoreDoc.doc);
            } catch (IOException e) {
                e.printStackTrace();
            }
            return null;
        }).filter(Objects::nonNull)
                .filter(doc -> filesForProfile.stream().noneMatch(file -> file.getName().equals(doc.get(FILENAME_FIELD))))
                .collect(Collectors.toList());

        int k = 20;
        int i = 0;
        String profileFolder = path_profile.replaceFirst(".*classes\\\\", "");
        System.out.println("--------------------------");
        System.out.printf("Top %d must-read articles for %s are:\n", k, profileFolder);
        for(Document doc: filtered){
            String path = doc.get(FULLPATH_FIELD).replaceFirst(".*groups\\\\", "");
            System.out.println(path);

            if(i == k - 1){
                break;
            }
            i++;
        }
    }

    public static void main(String[] args) throws IOException {
        Directory directory = new RAMDirectory();
        String path_all = Main.class.getResource("/20_newsgroups").getPath().replaceFirst("/", "").replace("/", "\\");
        String path_profile_med_space = Main.class.getResource("/profile_med_and_space").getPath().replaceFirst("/", "").replace("/", "\\");
        String path_profile_med = Main.class.getResource("/profile_med").getPath().replaceFirst("/", "").replace("/", "\\");
        String path_profile_el_space = Main.class.getResource("/profile_electronics_and_space").getPath().replaceFirst("/", "").replace("/", "\\");
        String path_profile_space = Main.class.getResource("/profile_space").getPath().replaceFirst("/", "").replace("/", "\\");

        StandardAnalyzer analyzer = new StandardAnalyzer();
        //index all the documents
        IndexWriter indexWriter = createIndex(directory, analyzer);
        loadData(indexWriter, directory, path_all, analyzer);
        indexWriter.commit();
        indexWriter.close();

        getRecommendation(directory, path_profile_med_space, analyzer);
        getRecommendation(directory, path_profile_med, analyzer);
        getRecommendation(directory, path_profile_el_space, analyzer);
        getRecommendation(directory, path_profile_space, analyzer);

    }

}
