package packloader.util;

import arc.Core;
import arc.struct.ObjectMap;
import arc.util.I18NBundle;
import arc.util.io.PropertiesUtils;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * Centralized, null-safe access to PackLoader localization strings.
 *
 * <p>Mindustry v160 (Arc) {@link I18NBundle#get(String)} returns {@code "???key???"}
 * when a key is missing, and {@link Core#bundle} can be {@code null} before the client
 * bundle has been initialized. This helper falls back to the English bundle shipped with
 * the mod and returns the requested key if no translation exists in either bundle.</p>
 */
public final class Localization{
    private static final String DEFAULT_BUNDLE = "/bundles/bundle.properties";
    private static final I18NBundle fallbackFormatter = I18NBundle.createEmptyBundle();

    private static ObjectMap<String, String> fallbackProperties;

    private Localization(){}

    /** Returns localized text for {@code key}, the English fallback, or {@code key} itself. */
    public static String getLocalizedText(String key){
        if(key == null) return "";

        try{
            if(Core.bundle != null && Core.bundle.has(key)){
                String value = Core.bundle.get(key);
                if(value != null) return value;
            }
        }catch(Throwable ignored){
            // Fall through to the bundled English text if the game bundle is unusable.
        }

        String text = defaultText(key);
        return text != null ? text : key;
    }

    /** Returns formatted localized text, or {@code key} if no translation exists. */
    public static String getLocalizedText(String key, Object... args){
        if(key == null) return "";
        if(args == null || args.length == 0) return getLocalizedText(key);

        try{
            if(Core.bundle != null && Core.bundle.has(key)){
                String value = Core.bundle.format(key, args);
                if(value != null) return value;
            }
        }catch(Throwable ignored){
            // Fall through to the bundled English text if formatting fails.
        }

        String text = defaultText(key);
        if(text == null) return key;

        try{
            synchronized(fallbackFormatter){
                return fallbackFormatter.formatString(text, args);
            }
        }catch(Throwable ignored){
            return text;
        }
    }

    private static String defaultText(String key){
        return fallbackProperties().get(key);
    }

    private static ObjectMap<String, String> fallbackProperties(){
        if(fallbackProperties == null){
            synchronized(Localization.class){
                if(fallbackProperties == null){
                    ObjectMap<String, String> loaded = new ObjectMap<>();
                    try(InputStream in = Localization.class.getResourceAsStream(DEFAULT_BUNDLE)){
                        if(in != null){
                            PropertiesUtils.load(loaded, new InputStreamReader(in, StandardCharsets.UTF_8));
                        }
                    }catch(Throwable ignored){
                        // Keep the empty map; callers still receive a non-null safe string.
                    }
                    fallbackProperties = loaded;
                }
            }
        }

        return fallbackProperties;
    }
}
