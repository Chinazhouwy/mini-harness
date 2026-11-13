package com.chinazhouwy.miniharness;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

public record ModelConfig(
        String apiKey,
        String baseUrl,
        String model
) {

    private static ModelConfig from (Properties props) {
        return new ModelConfig(
                props.getProperty("miniharness.api.key"),
                props.getProperty("miniharness.base.url"),
                props.getProperty("miniharness.model")
        );
    }


    public static ModelConfig loadProperties() throws IOException {
        Properties props = new Properties();

        try (InputStream in = ModelConfig.class.getClassLoader()
                .getResourceAsStream("openrouter.local.properties")) {
            if (in == null) {
                throw new IOException("找不到 openrouter.local.properties");
            }
            props.load(in);
        }

        return from(props);
    }

}
