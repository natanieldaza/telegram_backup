/* Telegram_Backup
 * Copyright (C) 2016 Fabian Schlenz
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>. */

package de.fabianonline.telegram_backup.exporter

import java.io.File
import java.io.PrintWriter
import java.io.OutputStreamWriter
import java.io.FileOutputStream
import java.nio.charset.Charset
import java.io.FileWriter
import java.io.IOException
import java.io.FileNotFoundException
import java.net.URL
import org.apache.commons.io.FileUtils
import java.util.LinkedList
import java.util.HashMap
import java.time.LocalDate
import java.time.LocalTime
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter 
import java.sql.Time
import java.text.SimpleDateFormat

import com.github.mustachejava.DefaultMustacheFactory
import com.github.mustachejava.Mustache
import com.github.mustachejava.MustacheFactory
import de.fabianonline.telegram_backup.*
import com.github.badoualy.telegram.tl.api.*
import com.google.gson.*
import com.github.salomonbrys.kotson.*
       

import org.slf4j.Logger
import org.slf4j.LoggerFactory

class CSVLinkExporter(val db: Database, val file_base: String, val settings: Settings) {
	val logger = LoggerFactory.getLogger(CSVLinkExporter::class.java)
	val mustache = DefaultMustacheFactory().compile("templates/csv/links.csv")
	val dialogs = db.getListOfDialogsForExport()
	val chats = db.getListOfChatsForExport()
	val datetime_format = SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
	val base = file_base + "files" + File.separatorChar
	
	val invalid_entity_index = "[INVALID ENTITY INDEX]"

	fun export() {
		val today = LocalDateTime.of(LocalDate.now(), LocalTime.MIDNIGHT)
		val timezone = ZoneOffset.systemDefault()
		val days = if (settings.max_file_age==-1) 7 else settings.max_file_age

		// Create base dir
		logger.debug("Creating base dir")
		File(base).mkdirs()

		if (days > 0) {
			for (dayOffset in days downTo 1) {
				val day = today.minusDays(dayOffset.toLong())

				val start = day.toEpochSecond(timezone.rules.getOffset(day))
				val end = start + 24 * 60 * 60
				val filename = base + "links.${day.format(DateTimeFormatter.ISO_LOCAL_DATE)}.csv"
				if (!File(file_base + filename).exists()) {
					logger.debug("Range: {} to {}", start, end)
					println("Processing messages for ${day}...")
					exportToFile(start, end, filename)
				}
			}
		} else {
			println("Processing all messages...")
			exportToFile(0, Long.MAX_VALUE, base + "links.all.csv")
		}
	}

	fun exportToFile(start: Long, end: Long, filename: String) {

		//val messages: List<Map<String, Any>> = db.getMessagesForCSVExport(start, end)
		val list = mutableListOf<Map<String, String?>>()
		val parser = JsonParser()
		//logger.debug("Got {} messages", messages.size)
		db.getMessagesForCSVExport(start, end) {data: HashMap<String, Any> ->
			//val msg: TLMessage = data.get("message_object") as TLMessage
			val json = parser.parse(data.get("json") as String).obj
			if (!json.contains("entities")) return@getMessagesForCSVExport
			
			val urls: List<String>? = json["entities"].array.filter{it.obj.isA("messageEntityTextUrl") || it.obj.isA("messageEntityUrl")}?.map {
				var url: String
				try {
					url = if (it.obj.contains("url")) it["url"].string else json["message"].string.substring(it["offset"].int, it["offset"].int + it["length"].int)
					if (!url.toLowerCase().startsWith("http:") && !url.toLowerCase().startsWith("https://")) url = "http://${url}"
				} catch (e: StringIndexOutOfBoundsException) {
					url = invalid_entity_index
				}
				url
			}

			if (urls != null) for(url in urls) {
				val scope = HashMap<String, String?>()
				scope.put("url", url)
				if (url == invalid_entity_index) {
					scope.put("host", invalid_entity_index)
				} else {
					scope.put("host", URL(url).getHost())
				}
				val timestamp = data["time"] as Time
				scope.put("time", datetime_format.format(timestamp))
				scope.put("username", if (data["user_username"]!=null) data["user_username"] as String else null)
				if (data["source_type"]=="dialog") {
					scope.put("chat_name", "@" + (dialogs.firstOrNull{it.id==data["source_id"]}?.username ?: ""))
				} else {
					scope.put("chat_name", chats.firstOrNull{it.id==data["source_id"]}?.name)
				}
				list.add(scope)
			}
		}
		val writer = getWriter(filename)
		mustache.execute(writer, mapOf("links" to list))
		writer.close()
	}
	
	private fun getWriter(filename: String): OutputStreamWriter {
		logger.trace("Creating writer for file {}", filename.anonymize())
		return OutputStreamWriter(FileOutputStream(filename), Charset.forName("UTF-8").newEncoder())
	}
}
