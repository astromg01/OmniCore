from pathlib import Path

activity = Path("app/src/main/java/com/omnicore/emulator/emulation/EmulationActivity.kt")
text = activity.read_text()

text = text.replace(
    'if (stagedUris.add(source.uri.toString())) stageDocument(source.uri, File(dir, safeName), descriptors)',
    'if (stagedUris.add(source.uri.toString())) stageDocument(source.uri, File(dir, safeName), descriptors, forceCopy = true)'
)
text = text.replace(
    'if (stagedUris.add(source.uri.toString())) stageDocument(source.uri, File(dir, safeFileName(source.name)), descriptors)',
    'if (stagedUris.add(source.uri.toString())) stageDocument(source.uri, File(dir, safeFileName(source.name)), descriptors, forceCopy = true)'
)

old_cue = '''            val localCue = File(dir, "game.cue")
            localCue.writeText(SafGameSource.rewriteCueReferences(cueText, resolvedNames), Charsets.UTF_8)
            ensurePreparationActive()
            PreparedContent(localCue.absolutePath, descriptors.toList(), dir)'''
new_cue = '''            val localCue = File(dir, "game.cue")
            val rewrittenCue = SafGameSource.rewriteCueReferences(cueText, resolvedNames)
                .removePrefix("\\uFEFF")
            localCue.writeText(rewrittenCue, Charsets.UTF_8)
            validateCueSession(localCue)
            ensurePreparationActive()
            PreparedContent(localCue.absolutePath, descriptors.toList(), dir)'''
if old_cue not in text:
    raise SystemExit("CUE write block not found")
text = text.replace(old_cue, new_cue)

old_stage = '''    private fun stageDocument(uri: Uri, target: File, retainedDescriptors: MutableList<ParcelFileDescriptor>) {
        ensurePreparationActive()
        target.parentFile?.mkdirs()
        target.delete()
        val descriptor = requireNotNull(contentResolver.openFileDescriptor(uri, "r")) {
            "O Android não forneceu acesso a ${target.name}."
        }
        val procPath = "/proc/self/fd/${descriptor.fd}"
        val seekable = runCatching {
            Os.lseek(descriptor.fileDescriptor, 0L, OsConstants.SEEK_CUR)
            true
        }.getOrDefault(false)
        val linked = seekable && runCatching {
            Os.symlink(procPath, target.absolutePath)
            true
        }.getOrDefault(false)
        if (linked) {
            retainedDescriptors += descriptor
            return
        }
        runCatching { descriptor.close() }
        contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "Não consegui ler ${target.name}." }
            target.outputStream().buffered(COPY_BUFFER_BYTES).use { output ->
                val buffer = ByteArray(COPY_BUFFER_BYTES)
                while (true) {
                    ensurePreparationActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    output.write(buffer, 0, count)
                }
            }
        }
    }
'''

new_stage = '''    private fun stageDocument(
        uri: Uri,
        target: File,
        retainedDescriptors: MutableList<ParcelFileDescriptor>,
        forceCopy: Boolean = false
    ) {
        ensurePreparationActive()
        target.parentFile?.mkdirs()
        target.delete()
        val descriptor = requireNotNull(contentResolver.openFileDescriptor(uri, "r")) {
            "O Android não forneceu acesso a ${target.name}."
        }

        if (!forceCopy) {
            val procPath = "/proc/self/fd/${descriptor.fd}"
            val seekable = runCatching {
                Os.lseek(descriptor.fileDescriptor, 0L, OsConstants.SEEK_CUR)
                true
            }.getOrDefault(false)
            val linked = seekable && runCatching {
                Os.symlink(procPath, target.absolutePath)
                true
            }.getOrDefault(false)
            if (linked) {
                retainedDescriptors += descriptor
                return
            }
        }

        // CUE/BIN tracks are materialized as real local files because the core
        // re-opens every track with stdio/fstat/seek. Some Android providers
        // expose seekable descriptors that cannot safely be reopened through
        // a /proc/self/fd symlink.
        runCatching { descriptor.close() }
        contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "Não consegui ler ${target.name}." }
            target.outputStream().buffered(COPY_BUFFER_BYTES).use { output ->
                val buffer = ByteArray(COPY_BUFFER_BYTES)
                while (true) {
                    ensurePreparationActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    output.write(buffer, 0, count)
                }
            }
        }
        require(target.isFile && target.length() > 0L) {
            "A faixa ${target.name} foi copiada vazia ou ficou inacessível."
        }
    }

    private fun validateCueSession(cueFile: File) {
        val cueText = cueFile.readText(Charsets.UTF_8)
        val references = SafGameSource.cueReferences(cueText)
        require(references.isNotEmpty()) { "O CUE local ficou sem referências FILE válidas." }

        references.forEach { reference ->
            ensurePreparationActive()
            val name = SafGameSource.normalizeReference(reference)
            val track = File(cueFile.parentFile, name)
            require(track.parentFile?.canonicalFile == cueFile.parentFile?.canonicalFile) {
                "Referência insegura no CUE: $name"
            }
            require(track.isFile && track.length() > 0L) {
                "A faixa '$name' não ficou disponível na sessão local."
            }
            runCatching {
                java.io.RandomAccessFile(track, "r").use { file ->
                    val length = file.length()
                    require(length > 0L)
                    file.seek((length - 1L).coerceAtLeast(0L))
                    require(file.read() >= 0)
                }
            }.getOrElse {
                error("A faixa '$name' não aceita leitura aleatória necessária para emulação de CD.")
            }
        }
    }
'''

if old_stage not in text:
    raise SystemExit("stageDocument block not found")
activity.write_text(text.replace(old_stage, new_stage))

saf = Path("app/src/main/java/com/omnicore/emulator/storage/SafGameSource.kt")
s = saf.read_text()
old_read = '''        val utf8 = bytes.toString(Charsets.UTF_8)
        return if ('\\uFFFD' in utf8) bytes.toString(Charsets.ISO_8859_1) else utf8'''
new_read = '''        val utf8 = bytes.toString(Charsets.UTF_8)
        val decoded = if ('\\uFFFD' in utf8) bytes.toString(Charsets.ISO_8859_1) else utf8
        return decoded.removePrefix("\\uFEFF").replace("\\r\\n", "\\n").replace('\\r', '\\n')'''
if old_read not in s:
    raise SystemExit("readCueText block not found")
saf.write_text(s.replace(old_read, new_read))

gradle = Path("app/build.gradle.kts")
g = gradle.read_text()
g = g.replace("versionCode = 4", "versionCode = 5")
g = g.replace('versionName = "0.3.0"', 'versionName = "0.3.1"')
gradle.write_text(g)
