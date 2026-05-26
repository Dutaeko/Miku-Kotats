package org.koitharu.kotatsu.miku

fun stableId(vararg parts: String): Long {
	var result = 1125899906842597L
	for (part in parts) {
		for (char in part) {
			result = 31L * result + char.code
		}
	}
	return result
}
