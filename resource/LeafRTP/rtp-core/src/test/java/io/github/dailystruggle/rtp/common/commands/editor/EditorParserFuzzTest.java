package io.github.dailystruggle.rtp.common.commands.editor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.code_intelligence.jazzer.api.FuzzedDataProvider;
import com.code_intelligence.jazzer.junit.FuzzTest;
import io.github.dailystruggle.rtp.common.commands.editor.channel.EditorLoopbackJson;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.DisplayName;

@DisplayName("Editor JSON, Payload, and Biome Store Coverage-Guided Fuzz Tests")
class EditorParserFuzzTest {

    @FuzzTest(maxDuration = "2s")
    @DisplayName("Fuzz EditorLoopbackJson.parse: only IllegalArgumentException may escape")
    void fuzzEditorLoopbackJson(String json) {
        try {
            EditorLoopbackJson.parse(json);
        } catch (IllegalArgumentException expected) {
            // Expected fail-closed rejection for malformed JSON
        }
    }

    @FuzzTest(maxDuration = "2s")
    @DisplayName("Fuzz EditorSessionManager.parseAndValidatePayload: only IllegalArgumentException may escape")
    void fuzzEditorPayload(FuzzedDataProvider data) {
        if (data == null) {
            return;
        }
        boolean requireDigest = data.consumeBoolean();
        String json = data.consumeRemainingAsString();
        try {
            EditorSessionManager.getInstance().parseAndValidatePayload(json, requireDigest);
        } catch (IllegalArgumentException expected) {
            // Expected fail-closed rejection (MissingDigestException extends IllegalArgumentException)
        }
    }

    @FuzzTest(maxDuration = "2s")
    @DisplayName("Fuzz BiomeBinCodec.decode: only IllegalArgumentException may escape")
    void fuzzBiomeBinCodec(byte[] runs) {
        try {
            int[] cells = BiomeBinCodec.decode(runs);
            assertNotNull(cells);
            assertEquals(BiomeBinCodec.CELLS, cells.length);
        } catch (IllegalArgumentException expected) {
            // Expected fail-closed rejection for truncated or overflowing varint runs
        }
    }

    @FuzzTest(maxDuration = "2s")
    @DisplayName("Fuzz WorldBiomeStore.load: only IOException or IllegalArgumentException may escape")
    void fuzzWorldBiomeStoreLoad(FuzzedDataProvider data) throws IOException {
        if (data == null) {
            return;
        }
        Path tempFile = Files.createTempFile("rtp-biome-store-fuzz-", ".bin");
        try {
            boolean structuredGzip = data.consumeBoolean();
            if (structuredGzip) {
                ByteArrayOutputStream raw = new ByteArrayOutputStream();
                try (DataOutputStream out = new DataOutputStream(new GZIPOutputStream(raw))) {
                    out.writeInt(data.consumeBoolean() ? 0x52545042 : data.consumeInt());
                    out.writeInt(data.consumeBoolean() ? 1 : data.consumeInt());
                    out.writeUTF(data.consumeBoolean() ? "world" : data.consumeString(16));
                    int palSize = data.consumeInt(0, 8);
                    out.writeInt(palSize);
                    for (int i = 0; i < palSize; i++) {
                        out.writeUTF(data.consumeString(16));
                    }
                    int count = data.consumeInt(0, 4);
                    out.writeInt(count);
                    byte[] rest = data.consumeRemainingAsBytes();
                    out.write(rest);
                }
                Files.write(tempFile, raw.toByteArray());
            } else {
                Files.write(tempFile, data.consumeRemainingAsBytes());
            }

            WorldBiomeStore store = new WorldBiomeStore("world", tempFile);
            try {
                store.load(tempFile);
            } catch (IOException | IllegalArgumentException expected) {
                // Expected fail-closed rejection for malformed store stream or runs
            }
            // Also verify the no-arg load() wrapper swallows and clears safely without throwing
            store.load();
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }
}
