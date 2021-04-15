/*
 * Copyright (C) 2018-2020. Huawei Technologies Co., Ltd. All rights reserved.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.trino.filesystem;

import io.trino.spi.filesystem.FileSystemClient;
import io.trino.spi.filesystem.FileSystemClientFactory;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;

/**
 * Factory which provides {@link LocalFileSystemClient}
 *
 * @since 2020-03-30
 */
public class LocalFileSystemClientFactory
        implements FileSystemClientFactory
{
    private static final String NAME_LOCAL = "local";

    @Override
    public FileSystemClient getFileSystemClient(Properties properties)
    {
        return new LocalFileSystemClient(new LocalConfig(properties), Paths.get("/"));
    }

    @Override
    public FileSystemClient getFileSystemClient(Properties properties, Path root)
    {
        return new LocalFileSystemClient(new LocalConfig(properties), root);
    }

    @Override
    public String getName()
    {
        return NAME_LOCAL;
    }
}
