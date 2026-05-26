package org.koitharu.kotatsu.miku

import moe.miku.app.parser.MangaSettingsManager
import org.koitharu.kotatsu.parsers.model.MangaSource

sealed class MikuMangaSource(
	override val name: String,
	val sourceId: String,
	val title: String,
	val domain: String,
) : MangaSource {
	data object Komikcast : MikuMangaSource("MIKU_KOMIKCAST", MangaSettingsManager.MANGA_SOURCE_KOMIKCAST, "KomikCast", "https://v2.komikcast.fit")
	data object Shinigami : MikuMangaSource("MIKU_SHINIGAMI", MangaSettingsManager.MANGA_SOURCE_SHINIGAMI, "Shinigami", "https://shinigami.asia")
	data object DoujinDesu : MikuMangaSource("MIKU_DOUJINDESU", MangaSettingsManager.MANGA_SOURCE_DOUJINDESU, "DoujinDesu", "https://doujindesu.tv")
	data object Westmanga : MikuMangaSource("MIKU_WESTMANGA", MangaSettingsManager.MANGA_SOURCE_WESTMANGA, "Westmanga", "https://westmanga.fun")
	data object BacaKomik : MikuMangaSource("MIKU_BACAKOMIK", MangaSettingsManager.MANGA_SOURCE_BACAKOMIK, "BacaKomik", "https://bacakomik.my")
	data object Komikindo : MikuMangaSource("MIKU_KOMIKINDO", MangaSettingsManager.MANGA_SOURCE_KOMIKINDO, "Komikindo", "https://komikindo2.com")
	data object Ikiru : MikuMangaSource("MIKU_IKIRU", MangaSettingsManager.MANGA_SOURCE_IKIRU, "Ikiru", "https://ikiru.my.id")
	data object Komiku : MikuMangaSource("MIKU_KOMIKU", MangaSettingsManager.MANGA_SOURCE_KOMIKU, "Komiku", "https://komiku.id")
	data object Mangasusu : MikuMangaSource("MIKU_MANGASUSU", MangaSettingsManager.MANGA_SOURCE_MANGASUSU, "Mangasusu", "https://mangasusu.co.in")
	data object KomikuOrg : MikuMangaSource("MIKU_KOMIKU_ORG", MangaSettingsManager.MANGA_SOURCE_KOMIKU_ORG, "Komiku Org", "https://api.komiku.org")
	data object CosmicScans : MikuMangaSource("MIKU_COSMICSCANS", MangaSettingsManager.MANGA_SOURCE_COSMICSCANS, "CosmicScans", "https://cosmicscans.id")
	data object Kiryuu : MikuMangaSource("MIKU_KIRYUU", MangaSettingsManager.MANGA_SOURCE_KIRYUU, "Kiryuu", "https://kiryuu.one")
	data object KiryuuOfficial : MikuMangaSource("MIKU_KIRYUU_OFFICIAL", MangaSettingsManager.MANGA_SOURCE_KIRYUU_OFFICIAL, "Kiryuu Official", "https://kiryuu02.com")
	data object Natsu : MikuMangaSource("MIKU_NATSU", MangaSettingsManager.MANGA_SOURCE_NATSU, "Natsu", "https://natsu.id")
	data object Ainzscanss : MikuMangaSource("MIKU_AINZSCANSS", MangaSettingsManager.MANGA_SOURCE_AINZSCANSS, "Ainzscanss", "https://ainzscanss.com")
	data object Apkomik : MikuMangaSource("MIKU_APKOMIK", MangaSettingsManager.MANGA_SOURCE_APKOMIK, "Apkomik", "https://apkomik.cc")

	companion object {
		val all: Set<MikuMangaSource> = linkedSetOf(
			Komikcast,
			Shinigami,
			DoujinDesu,
			Westmanga,
			BacaKomik,
			Komikindo,
			Ikiru,
			Komiku,
			Mangasusu,
			KomikuOrg,
			CosmicScans,
			Kiryuu,
			KiryuuOfficial,
			Natsu,
			Ainzscanss,
			Apkomik,
		)

		fun fromName(name: String?): MikuMangaSource? = all.find { it.name == name }
	}
}
