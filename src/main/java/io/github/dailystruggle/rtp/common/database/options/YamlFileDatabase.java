package io.github.dailystruggle.rtp.common.database.options;

import java.io.File;

/**
 * a "database" that's just reading and writing yaml files,
 *      using SimpleYaml (a server independent yaml library that can preserve comments)
 */
public interface YamlFileDatabase {
    File getMainDirectory();
}
