package io.github.dailystruggle.rtp.common.importer;

import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Result of an import operation performed by a {@link ForeignConfigImporter}.
 */
public final class ImportResult {
    private final boolean success;
    private final String sourceName;
    private final List<Path> writtenFiles;
    private final List<String> warnings;
    private final List<String> errors;
    private final List<String> mappedEntities;

    public ImportResult(boolean success,
                        String sourceName,
                        List<Path> writtenFiles,
                        List<String> warnings,
                        List<String> errors,
                        List<String> mappedEntities) {
        this.success = success;
        this.sourceName = Objects.requireNonNull(sourceName, "sourceName must not be null");
        this.writtenFiles = writtenFiles != null ? Collections.unmodifiableList(writtenFiles) : Collections.emptyList();
        this.warnings = warnings != null ? Collections.unmodifiableList(warnings) : Collections.emptyList();
        this.errors = errors != null ? Collections.unmodifiableList(errors) : Collections.emptyList();
        this.mappedEntities = mappedEntities != null ? Collections.unmodifiableList(mappedEntities) : Collections.emptyList();
    }

    public static ImportResult success(String sourceName,
                                       List<Path> writtenFiles,
                                       List<String> warnings,
                                       List<String> mappedEntities) {
        return new ImportResult(true, sourceName, writtenFiles, warnings, Collections.emptyList(), mappedEntities);
    }

    public static ImportResult failure(String sourceName,
                                       List<String> errors,
                                       List<String> warnings) {
        return new ImportResult(false, sourceName, Collections.emptyList(), warnings, errors, Collections.emptyList());
    }

    public boolean isSuccess() {
        return success;
    }

    public String getSourceName() {
        return sourceName;
    }

    public List<Path> getWrittenFiles() {
        return writtenFiles;
    }

    public List<String> getWarnings() {
        return warnings;
    }

    public List<String> getErrors() {
        return errors;
    }

    public List<String> getMappedEntities() {
        return mappedEntities;
    }

    @Override
    public String toString() {
        return "ImportResult{" +
                "success=" + success +
                ", sourceName='" + sourceName + '\'' +
                ", writtenFiles=" + writtenFiles.size() +
                ", warnings=" + warnings.size() +
                ", errors=" + errors.size() +
                ", mappedEntities=" + mappedEntities.size() +
                '}';
    }
}
