package io.github.dailystruggle.rtp.common.database;

import org.jetbrains.annotations.Nullable;

import java.io.File;

public interface databaseInterface {
    String name();

    File getMainDirectory();

    boolean connect();

    @Nullable
    Object get(String path);


}
