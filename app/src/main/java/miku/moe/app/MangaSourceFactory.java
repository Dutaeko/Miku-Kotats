package miku.moe.app;

import android.content.Context;
import java.util.ArrayList;

public final class MangaSourceFactory {
    private static final KomikcastClient KOMIKCAST = new KomikcastClient();
    private static final Shinigami SHINIGAMI = new Shinigami();
    private static final DoujinDesu DOUJINDESU = new DoujinDesu();
    private static final Westmanga WESTMANGA = new Westmanga();
    private static final BacaKomik BACAKOMIK = new BacaKomik();
    private static final KomikIndo KOMIKINDO = new KomikIndo();
    private static final Ikiru IKIRU = new Ikiru();
    private static final Komiku KOMIKU = new Komiku();
    private static final Mangasusu MANGASUSU = new Mangasusu();
    private static final KomikuOrg KOMIKU_ORG = new KomikuOrg();
    private static final CosmicScans COSMICSCANS = new CosmicScans();
    private static final Kiryuu KIRYUU = new Kiryuu();
    private static final KiryuuOfficial KIRYUU_OFFICIAL = new KiryuuOfficial();
    private static final Natsu NATSU = new Natsu();
    private static final Ainzscanss AINZSCANSS = new Ainzscanss();
    private static final Apkomik APKOMIK = new Apkomik();

    private MangaSourceFactory() {}

    public static KomikcastClient create(Context context) {
        return createBySourceId(context == null ? MangaSettingsManager.MANGA_SOURCE_KOMIKCAST : MangaSettingsManager.getMangaSource(context));
    }

    public static KomikcastClient createFor(MangaPost manga, Context fallbackContext) {
        if (manga != null) return createBySourceId(manga.getSourceId());
        return create(fallbackContext);
    }

    public static KomikcastClient createBySourceId(String sourceId) {
        if (MangaSettingsManager.MANGA_SOURCE_SHINIGAMI.equals(sourceId)) return SHINIGAMI;
        if (MangaSettingsManager.MANGA_SOURCE_DOUJINDESU.equals(sourceId)) return DOUJINDESU;
        if (MangaSettingsManager.MANGA_SOURCE_WESTMANGA.equals(sourceId)) return WESTMANGA;
        if (MangaSettingsManager.MANGA_SOURCE_BACAKOMIK.equals(sourceId)) return BACAKOMIK;
        if (MangaSettingsManager.MANGA_SOURCE_KOMIKINDO.equals(sourceId)) return KOMIKINDO;
        if (MangaSettingsManager.MANGA_SOURCE_IKIRU.equals(sourceId)) return IKIRU;
        if (MangaSettingsManager.MANGA_SOURCE_KOMIKU.equals(sourceId)) return KOMIKU;
        if (MangaSettingsManager.MANGA_SOURCE_MANGASUSU.equals(sourceId)) return MANGASUSU;
        if (MangaSettingsManager.MANGA_SOURCE_KOMIKU_ORG.equals(sourceId)) return KOMIKU_ORG;
        if (MangaSettingsManager.MANGA_SOURCE_COSMICSCANS.equals(sourceId)) return COSMICSCANS;
        if (MangaSettingsManager.MANGA_SOURCE_KIRYUU.equals(sourceId)) return KIRYUU;
        if (MangaSettingsManager.MANGA_SOURCE_KIRYUU_OFFICIAL.equals(sourceId)) return KIRYUU_OFFICIAL;
        if (MangaSettingsManager.MANGA_SOURCE_NATSU.equals(sourceId)) return NATSU;
        if (MangaSettingsManager.MANGA_SOURCE_AINZSCANSS.equals(sourceId)) return AINZSCANSS;
        if (MangaSettingsManager.MANGA_SOURCE_APKOMIK.equals(sourceId)) return APKOMIK;
        return KOMIKCAST;
    }

    public static String getActiveSourceLabel(Context context) {
        return labelForSourceId(context == null ? MangaSettingsManager.MANGA_SOURCE_KOMIKCAST : MangaSettingsManager.getMangaSource(context));
    }

    public static String[] allSourceIds() {
        return new String[]{
                MangaSettingsManager.MANGA_SOURCE_KOMIKCAST,
                MangaSettingsManager.MANGA_SOURCE_SHINIGAMI,
                MangaSettingsManager.MANGA_SOURCE_DOUJINDESU,
                MangaSettingsManager.MANGA_SOURCE_WESTMANGA,
                MangaSettingsManager.MANGA_SOURCE_BACAKOMIK,
                MangaSettingsManager.MANGA_SOURCE_KOMIKINDO,
                MangaSettingsManager.MANGA_SOURCE_IKIRU,
                MangaSettingsManager.MANGA_SOURCE_KOMIKU,
                MangaSettingsManager.MANGA_SOURCE_MANGASUSU,
                MangaSettingsManager.MANGA_SOURCE_KOMIKU_ORG,
                MangaSettingsManager.MANGA_SOURCE_COSMICSCANS,
                MangaSettingsManager.MANGA_SOURCE_KIRYUU,
                MangaSettingsManager.MANGA_SOURCE_KIRYUU_OFFICIAL,
                MangaSettingsManager.MANGA_SOURCE_NATSU,
                MangaSettingsManager.MANGA_SOURCE_AINZSCANSS,
                MangaSettingsManager.MANGA_SOURCE_APKOMIK
        };
    }

    public static ArrayList<String> enabledSourceIds(Context context) {
        if (context == null) {
            ArrayList<String> result = new ArrayList<>();
            for (String source : allSourceIds()) result.add(source);
            return result;
        }
        return MangaSettingsManager.getEnabledMangaSources(context);
    }

    public static String labelForSourceId(String sourceId) {
        if (MangaSettingsManager.MANGA_SOURCE_SHINIGAMI.equals(sourceId)) return "Shinigami";
        if (MangaSettingsManager.MANGA_SOURCE_DOUJINDESU.equals(sourceId)) return "DoujinDesu";
        if (MangaSettingsManager.MANGA_SOURCE_WESTMANGA.equals(sourceId)) return "Westmanga";
        if (MangaSettingsManager.MANGA_SOURCE_BACAKOMIK.equals(sourceId)) return "BacaKomik";
        if (MangaSettingsManager.MANGA_SOURCE_KOMIKINDO.equals(sourceId)) return "Komikindo";
        if (MangaSettingsManager.MANGA_SOURCE_IKIRU.equals(sourceId)) return "Ikiru";
        if (MangaSettingsManager.MANGA_SOURCE_KOMIKU.equals(sourceId)) return "Komiku";
        if (MangaSettingsManager.MANGA_SOURCE_MANGASUSU.equals(sourceId)) return "Mangasusu";
        if (MangaSettingsManager.MANGA_SOURCE_KOMIKU_ORG.equals(sourceId)) return "Komiku Org";
        if (MangaSettingsManager.MANGA_SOURCE_COSMICSCANS.equals(sourceId)) return "CosmicScans";
        if (MangaSettingsManager.MANGA_SOURCE_KIRYUU.equals(sourceId)) return "Kiryuu";
        if (MangaSettingsManager.MANGA_SOURCE_KIRYUU_OFFICIAL.equals(sourceId)) return "Kiryuu Official";
        if (MangaSettingsManager.MANGA_SOURCE_NATSU.equals(sourceId)) return "Natsu";
        if (MangaSettingsManager.MANGA_SOURCE_AINZSCANSS.equals(sourceId)) return "Ainzscanss";
        if (MangaSettingsManager.MANGA_SOURCE_APKOMIK.equals(sourceId)) return "Apkomik";
        return "KomikCast";
    }

    public static String iconForSourceId(String sourceId) {
        if (MangaSettingsManager.MANGA_SOURCE_SHINIGAMI.equals(sourceId)) return "https://www.google.com/s2/favicons?sz=128&domain_url=" + MangaSettingsManager.getSourceDomain(MangaSettingsManager.MANGA_SOURCE_SHINIGAMI);
        if (MangaSettingsManager.MANGA_SOURCE_DOUJINDESU.equals(sourceId)) return "https://www.google.com/s2/favicons?sz=128&domain_url=" + MangaSettingsManager.getSourceDomain(MangaSettingsManager.MANGA_SOURCE_DOUJINDESU);
        if (MangaSettingsManager.MANGA_SOURCE_WESTMANGA.equals(sourceId)) return "https://www.google.com/s2/favicons?sz=128&domain_url=" + MangaSettingsManager.getSourceDomain(MangaSettingsManager.MANGA_SOURCE_WESTMANGA);
        if (MangaSettingsManager.MANGA_SOURCE_BACAKOMIK.equals(sourceId)) return "https://www.google.com/s2/favicons?sz=128&domain_url=" + MangaSettingsManager.getSourceDomain(MangaSettingsManager.MANGA_SOURCE_BACAKOMIK);
        if (MangaSettingsManager.MANGA_SOURCE_KOMIKINDO.equals(sourceId)) return "https://www.google.com/s2/favicons?sz=128&domain_url=" + MangaSettingsManager.getSourceDomain(MangaSettingsManager.MANGA_SOURCE_KOMIKINDO);
        if (MangaSettingsManager.MANGA_SOURCE_IKIRU.equals(sourceId)) return "https://www.google.com/s2/favicons?sz=128&domain_url=" + MangaSettingsManager.getSourceDomain(MangaSettingsManager.MANGA_SOURCE_IKIRU);
        if (MangaSettingsManager.MANGA_SOURCE_KOMIKU.equals(sourceId)) return "https://www.google.com/s2/favicons?sz=128&domain_url=" + MangaSettingsManager.getSourceDomain(MangaSettingsManager.MANGA_SOURCE_KOMIKU);
        if (MangaSettingsManager.MANGA_SOURCE_MANGASUSU.equals(sourceId)) return "https://www.google.com/s2/favicons?sz=128&domain_url=" + MangaSettingsManager.getSourceDomain(MangaSettingsManager.MANGA_SOURCE_MANGASUSU);
        if (MangaSettingsManager.MANGA_SOURCE_KOMIKU_ORG.equals(sourceId)) return "https://www.google.com/s2/favicons?sz=128&domain_url=" + MangaSettingsManager.getSourceDomain(MangaSettingsManager.MANGA_SOURCE_KOMIKU_ORG);
        if (MangaSettingsManager.MANGA_SOURCE_COSMICSCANS.equals(sourceId)) return "android.resource://miku.moe.app/drawable/ic_source_cosmicscans";
        if (MangaSettingsManager.MANGA_SOURCE_KIRYUU.equals(sourceId)) return "https://www.google.com/s2/favicons?sz=128&domain_url=" + MangaSettingsManager.getSourceDomain(MangaSettingsManager.MANGA_SOURCE_KIRYUU);
        if (MangaSettingsManager.MANGA_SOURCE_KIRYUU_OFFICIAL.equals(sourceId)) return "https://www.google.com/s2/favicons?sz=128&domain_url=" + MangaSettingsManager.getSourceDomain(MangaSettingsManager.MANGA_SOURCE_KIRYUU_OFFICIAL);
        if (MangaSettingsManager.MANGA_SOURCE_NATSU.equals(sourceId)) return "https://www.google.com/s2/favicons?sz=128&domain_url=" + MangaSettingsManager.getSourceDomain(MangaSettingsManager.MANGA_SOURCE_NATSU);
        if (MangaSettingsManager.MANGA_SOURCE_AINZSCANSS.equals(sourceId)) return "https://www.google.com/s2/favicons?sz=128&domain_url=" + MangaSettingsManager.getSourceDomain(MangaSettingsManager.MANGA_SOURCE_AINZSCANSS);
        if (MangaSettingsManager.MANGA_SOURCE_APKOMIK.equals(sourceId)) return "https://www.google.com/s2/favicons?sz=128&domain_url=" + MangaSettingsManager.getSourceDomain(MangaSettingsManager.MANGA_SOURCE_APKOMIK);
        return "https://www.google.com/s2/favicons?sz=128&domain_url=" + MangaSettingsManager.getSourceDomain(MangaSettingsManager.MANGA_SOURCE_KOMIKCAST);
    }
}
