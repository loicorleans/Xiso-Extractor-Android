import com.xiso.extractor.XisoReader;
import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.List;

/** PC test: javac -d out app/src/main/java/com/xiso/extractor/XisoReader.java tools/XisoTest.java && java -cp out XisoTest game.iso outdir */
public class XisoTest {
    public static void main(String[] a) throws Exception {
        try (FileChannel ch = FileChannel.open(Paths.get(a[0]), StandardOpenOption.READ)) {
            XisoReader r = new XisoReader(ch);
            List<XisoReader.Entry> list = r.list(false);
            System.out.println("disc offset 0x" + Long.toHexString(r.discOffset()) + ", entries " + list.size()
                    + ", bytes " + XisoReader.totalBytes(list));
            Path root = Paths.get(a[1]);
            byte[] buf = new byte[1 << 20];
            for (XisoReader.Entry e : list) {
                Path p = root.resolve(e.path);
                if (e.dir) { Files.createDirectories(p); continue; }
                try (OutputStream os = Files.newOutputStream(p)) { r.extract(e, os, buf, null, null); }
            }
        }
    }
}
