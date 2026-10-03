import forge.CardStorageReader;
import forge.StaticData;

// Minimal headless probe: load Forge's card pool with no GUI, no preferences,
// no Forge bootstrap — just the reader and StaticData — and report how many
// cards came back and what it cost in heap. Answers two questions at once:
// does the engine's data layer run headless, and what does a server need.
public class Probe {
    static long used() {
        Runtime r = Runtime.getRuntime();
        for (int i = 0; i < 4; i++) { System.gc(); try { Thread.sleep(120); } catch (Exception e) {} }
        return r.totalMemory() - r.freeMemory();
    }
    static String mb(long b) { return String.format("%.0f MB", b / 1024.0 / 1024.0); }

    public static void main(String[] args) throws Exception {
        String res = args.length > 0 ? args[0] : "forge-gui/res";
        forge.util.Lang.createInstance("en-US");
        forge.util.Localizer.getInstance().initialize("en-US", res + "/languages/");
        long before = used();
        long t0 = System.currentTimeMillis();

        CardStorageReader reader = new CardStorageReader(
                res + "/cardsfolder", CardStorageReader.ProgressObserver.emptyObserver, false);
        StaticData data = new StaticData(reader, null,
                res + "/editions", "/home/claude/probe/empty-editions", res + "/blockdata",
                "LatestCoreExp", true, false);

        long ms = System.currentTimeMillis() - t0;
        long after = used();

        System.out.println("cards loaded   : " + data.getCommonCards().getUniqueCards().size());
        System.out.println("editions       : " + data.getEditions().size());
        System.out.println("load time      : " + ms + " ms");
        System.out.println("heap for pool  : " + mb(after - before));
        System.out.println("heap total used: " + mb(after));
    }
}
