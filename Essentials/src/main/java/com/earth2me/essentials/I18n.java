package com.earth2me.essentials;

import com.earth2me.essentials.adventure.AdventureUtil;
import net.ess3.api.IEssentials;
import org.bukkit.configuration.file.YamlConfiguration;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.nio.file.Files;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.MissingResourceException;
import java.util.ResourceBundle;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.regex.Pattern;

public class I18n implements net.ess3.api.II18n {
    private static final String MESSAGES = "messages";
    private static final Pattern NODOUBLEMARK = Pattern.compile("''");
    private static final ExecutorService BUNDLE_LOADER_EXECUTOR = Executors.newFixedThreadPool(2);
    private static final ResourceBundle NULL_BUNDLE = new ResourceBundle() {
        @SuppressWarnings("NullableProblems")
        public Enumeration<String> getKeys() {
            return null;
        }

        protected Object handleGetObject(final @NotNull String key) {
            return null;
        }
    };
    private static I18n instance;
    private final transient Locale defaultLocale = Locale.getDefault();
    private final transient ResourceBundle defaultBundle;
    private final transient IEssentials ess;
    private transient Locale currentLocale = defaultLocale;
    private final transient Map<Locale, ResourceBundle> loadedBundles = new ConcurrentHashMap<>();
    private final transient List<Locale> loadingBundles = new ArrayList<>();
    private transient ResourceBundle localeBundle;
    private final transient Map<Locale, Map<String, MessageFormat>> messageFormatCache = new HashMap<>();

    public I18n(final IEssentials ess) {
        this.ess = ess;
        defaultBundle = ResourceBundle.getBundle(MESSAGES, Locale.ENGLISH, new YamlControl());
        localeBundle = defaultBundle;
    }

    /**
     * Translates a message using the server's configured locale.
     * @param tlKey The translation key.
     * @param objects Translation parameters, if applicable. Note: by default, these will not be parsed for MiniMessage.
     * @return The translated message.
     * @see AdventureUtil#parsed(String)
     */
    public static String tlLiteral(final String tlKey, final Object... objects) {
        if (instance == null) {
            return "";
        }

        return tlLocale(instance.currentLocale, tlKey, objects);
    }

    /**
     * Translates a message using the provided locale.
     * @param locale The locale to translate the key to.
     * @param tlKey The translation key.
     * @param objects Translation parameters, if applicable. Note: by default, these will not be parsed for MiniMessage.
     * @return The translated message.
     * @see AdventureUtil#parsed(String)
     */
    public static String tlLocale(final Locale locale, final String tlKey, final Object... objects) {
        if (instance == null) {
            return "";
        }
        if (objects.length == 0) {
            return NODOUBLEMARK.matcher(instance.translate(locale, tlKey)).replaceAll("'");
        } else {
            return instance.format(locale, tlKey, objects);
        }
    }

    public static String capitalCase(final String input) {
        return input == null || input.isEmpty() ? input : input.toUpperCase(Locale.ENGLISH).charAt(0) + input.toLowerCase(Locale.ENGLISH).substring(1);
    }

    public void onEnable() {
        instance = this;
    }

    public void onDisable() {
        instance = null;
    }

    @Override
    public Locale getCurrentLocale() {
        return currentLocale;
    }

    /**
     * Returns the {@link ResourceBundle} for the given {@link Locale}, if loaded. If a bundle is requested which is
     * not loaded, it will be loaded asynchronously and the default bundle will be returned in the meantime.
     */
    private ResourceBundle getBundle(final Locale locale) {
        if (loadedBundles.containsKey(locale)) {
            return loadedBundles.get(locale);
        } else {
            synchronized (loadingBundles) {
                if (!loadingBundles.contains(locale)) {
                    loadingBundles.add(locale);
                    BUNDLE_LOADER_EXECUTOR.submit(() -> {
                        blockingLoadBundle(locale);
                        synchronized (loadingBundles) {
                            loadingBundles.remove(locale);
                        }
                    });
                }
            }
            return defaultBundle;
        }
    }

    public void blockingLoadBundle(final Locale locale) {
        if (!loadedBundles.containsKey(locale)) {
            ResourceBundle bundle;
            try {
                bundle = ResourceBundle.getBundle(MESSAGES, locale, new FileResClassLoader(I18n.class.getClassLoader(), ess), new YamlControl());
            } catch (MissingResourceException ex) {
                try {
                    bundle = ResourceBundle.getBundle(MESSAGES, locale, new YamlControl());
                } catch (MissingResourceException ex2) {
                    bundle = NULL_BUNDLE;
                }
            }

            loadedBundles.put(locale, bundle);
        }
    }

    private String translate(final Locale locale, final String string) {
        try {
            try {
                return getBundle(locale).getString(string);
            } catch (final MissingResourceException ex) {
                return localeBundle.getString(string);
            }
        } catch (final MissingResourceException ex) {
            if (ess != null && ess.getSettings().isDebug()) {
                ess.getLogger().log(Level.WARNING, String.format("Missing translation key \"%s\" in translation file %s", ex.getKey(), localeBundle.getLocale().toString()), ex);
            }
            try {
                return defaultBundle.getString(string);
            } catch (final MissingResourceException ex2) {
                return string;
            }
        }
    }

    private String format(final Locale locale, final String string, final Object... objects) {
        String format = translate(locale, string);

        MessageFormat messageFormat = messageFormatCache.computeIfAbsent(locale, l -> new HashMap<>()).get(format);
        if (messageFormat == null) {
            try {
                messageFormat = new MessageFormat(format);
            } catch (final IllegalArgumentException e) {
                ess.getLogger().log(Level.SEVERE, "Invalid Translation key for '" + string + "': " + e.getMessage());
                format = format.replaceAll("\\{(\\D*?)}", "\\[$1\\]");
                messageFormat = new MessageFormat(format);
            }
            messageFormatCache.get(locale).put(format, messageFormat);
        }

        final Object[] processedArgs = mutateArgs(objects, arg -> {
            if (arg instanceof AdventureUtil.ParsedPlaceholder) {
                return arg.toString();
            }
            return ess.getAdventureFacet().legacyToMini(ess.getAdventureFacet().escapeTags(arg.toString()));
        });

        return messageFormat.format(processedArgs).replace(' ', ' '); // replace nbsp with a space
    }

    public static Object[] mutateArgs(final Object[] objects, final Function<Object, String> mutator) {
        final Object[] args = new Object[objects.length];
        for (int i = 0; i < objects.length; i++) {
            final Object object = objects[i];
            // MessageFormat will format these itself, troll face.
            if (object instanceof Number || object instanceof Date || object == null) {
                args[i] = object;
                continue;
            }

            args[i] = mutator.apply(object);
        }
        return args;
    }

    public void updateLocale(final String loc) {
        if (loc != null && !loc.isEmpty()) {
            currentLocale = getLocale(loc);
        }
        ResourceBundle.clearCache();
        loadedBundles.clear();
        messageFormatCache.clear();
        ess.getLogger().log(Level.INFO, String.format("Using locale %s", currentLocale.toString()));

        try {
            localeBundle = ResourceBundle.getBundle(MESSAGES, currentLocale, new YamlControl());
        } catch (final MissingResourceException ex) {
            localeBundle = NULL_BUNDLE;
        }
    }

    public static Locale getLocale(final String loc) {
        if (loc == null || loc.isEmpty()) {
            return instance.currentLocale;
        }
        final String[] parts = loc.split("[_.]");
        if (parts.length == 1) {
            return new Locale(parts[0]);
        }
        if (parts.length == 2) {
            return new Locale(parts[0], parts[1]);
        }
        if (parts.length == 3) {
            return new Locale(parts[0], parts[1], parts[2]);
        }
        return instance.currentLocale;
    }

    /**
     * Attempts to load properties files from the plugin directory before falling back to the jar.
     */
    private static class FileResClassLoader extends ClassLoader {
        private final transient File messagesFolder;

        FileResClassLoader(final ClassLoader classLoader, final IEssentials ess) {
            super(classLoader);
            this.messagesFolder = new File(ess.getDataFolder(), "messages");
            //noinspection ResultOfMethodCallIgnored
            this.messagesFolder.mkdirs();

            final File defaultFile = new File(messagesFolder, "messages_en.yml");
            if (!defaultFile.exists()) {
                try (final InputStream in = classLoader.getResourceAsStream("messages_en.yml")) {
                    if (in != null) {
                        Files.copy(in, defaultFile.toPath());
                    }
                } catch (final IOException e) {
                    ess.getLogger().log(Level.WARNING, "Could not save default messages_en.yml", e);
                }
            }
        }

        @Override
        public URL getResource(final String string) {
            final File file = new File(messagesFolder, string);
            if (file.exists()) {
                try {
                    return file.toURI().toURL();
                } catch (final MalformedURLException ignored) {
                }
            }
            return null;
        }

        @Override
        public InputStream getResourceAsStream(final String string) {
            final File file = new File(messagesFolder, string);
            if (file.exists()) {
                try {
                    return new FileInputStream(file);
                } catch (final FileNotFoundException ignored) {
                }
            }
            return null;
        }
    }

    private static final class YamlResourceBundle extends ResourceBundle {
        private final Map<String, String> messages;

        YamlResourceBundle(final Map<String, String> messages) {
            this.messages = messages;
        }

        @Override
        protected Object handleGetObject(final @NotNull String key) {
            return messages.get(key);
        }

        @NotNull
        @Override
        public Enumeration<String> getKeys() {
            return Collections.enumeration(messages.keySet());
        }
    }

    private static final class YamlControl extends ResourceBundle.Control {
        @Override
        public List<String> getFormats(final String baseName) {
            return Collections.singletonList("yml");
        }

        @Override
        public ResourceBundle newBundle(final String baseName, final Locale locale, final String format, final ClassLoader loader, final boolean reload) throws IOException {
            if (!format.equals("yml")) {
                return null;
            }
            final String resourceName = toBundleName(baseName, locale) + ".yml";
            ResourceBundle bundle = null;
            InputStream stream = null;
            if (reload) {
                final URL url = loader.getResource(resourceName);
                if (url != null) {
                    final URLConnection connection = url.openConnection();
                    if (connection != null) {
                        connection.setUseCaches(false);
                        stream = connection.getInputStream();
                    }
                }
            } else {
                stream = loader.getResourceAsStream(resourceName);
            }
            if (stream != null) {
                try (InputStreamReader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
                    final YamlConfiguration yaml = YamlConfiguration.loadConfiguration(reader);
                    final Map<String, String> messages = new HashMap<>();
                    for (final String key : yaml.getKeys(true)) {
                        if (yaml.isString(key)) {
                            messages.put(key, yaml.getString(key));
                        }
                    }
                    bundle = new YamlResourceBundle(messages);
                } finally {
                    stream.close();
                }
            }
            return bundle;
        }

        @Override
        public Locale getFallbackLocale(final String baseName, final Locale locale) {
            if (locale.equals(Locale.ENGLISH)) {
                return null;
            }
            return Locale.ENGLISH;
        }
    }
}
