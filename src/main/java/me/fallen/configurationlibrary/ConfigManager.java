package me.fallen.configurationlibrary;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class ConfigManager {

    private final File configDirectory;
    private final Map<Class<?>, Object> cache = new HashMap<>();

    public ConfigManager(File configDirectory) {
        this.configDirectory = configDirectory;
        if (!configDirectory.exists()) {
            configDirectory.mkdirs();
        }
    }

    @SuppressWarnings("unchecked")
    public <T> T registerConfig(Class<T> configClass) {
        if (!configClass.isAnnotationPresent(Config.class)) {
            throw new IllegalArgumentException("Interface must be annotated with @Config: " + configClass.getName());
        }

        File configFile = new File(configDirectory, configClass.getSimpleName() + ".yml");
        YamlConfiguration yamlConfig = YamlConfiguration.loadConfiguration(configFile);

        T proxyInstance = (T) Proxy.newProxyInstance(
                configClass.getClassLoader(),
                new Class<?>[]{configClass},
                new ConfigInvocationHandler(configClass, yamlConfig, configFile)
        );

        cache.put(configClass, proxyInstance);
        return proxyInstance;
    }

    @SuppressWarnings("unchecked")
    public <T> T getConfig(Class<T> configClass) {
        return (T) cache.get(configClass);
    }

    private static class ConfigInvocationHandler implements InvocationHandler {
        private final Class<?> configClass;
        private final YamlConfiguration yamlConfig;
        private final File configFile;
        private final ConfigurationSection configSection;

        public ConfigInvocationHandler(Class<?> configClass, ConfigurationSection configSection, File configFile) {
            this.configClass = configClass;
            this.yamlConfig = (configSection instanceof YamlConfiguration)
                    ? (YamlConfiguration) configSection
                    : null; // Это нужно для root-конфигов
            this.configSection = configSection;
            this.configFile = configFile;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            String key = method.getName().replaceAll("([a-z])([A-Z])", "$1-$2").toLowerCase();

            if (method.isAnnotationPresent(Node.class)) {
                Class<?> returnType = method.getReturnType();
                if (Map.class.isAssignableFrom(returnType)) {
                    return handleMap(key, method);
                }
                if (List.class.isAssignableFrom(returnType)) {
                    return handleList(key, method);
                }
                ConfigurationSection section = configSection.getConfigurationSection(key) != null
                        ? configSection.getConfigurationSection(key)
                        : configSection.createSection(key);
                return Proxy.newProxyInstance(
                        returnType.getClassLoader(),
                        new Class<?>[]{returnType},
                        new ConfigInvocationHandler(returnType, section, configFile)
                );
            }

            if (method.isDefault()) {
                return method.invoke(this, args);
            }

            if (method.getName().startsWith("set") && args != null && args.length == 1) {
                String setterKey = key.substring(4).toLowerCase();
                configSection.set(setterKey, args[0]);
                saveConfig();
                return null;
            }

            Object value = configSection.get(key);
            if (value == null) {
                value = getDefaultValue(method);
                configSection.set(key, value);
                saveConfig();
            }

            if (method.isAnnotationPresent(Serializer.class)) {
                Class<? extends SerializerInterface<?>> serializerClass = method.getAnnotation(Serializer.class).value();
                SerializerInterface<?> serializer = serializerClass.getDeclaredConstructor().newInstance();

                if (method.getName().startsWith("set") && args != null && args.length == 1) {
                    Object serializedValue = ((SerializerInterface<Object>) serializer).serialize(args[0]);
                    yamlConfig.set(key, serializedValue);
                    saveConfig();
                    return null;
                }

                Object rawValue = yamlConfig.get(key);
                return serializer.deserialize(rawValue);
            }

            return value;
        }

        private Object handleMap(String key, Method method) throws Exception {
            ConfigurationSection section = configSection.getConfigurationSection(key);
            if (section == null) return new HashMap<>();

            Class<?> valueType = (Class<?>) ((java.lang.reflect.ParameterizedType) method.getGenericReturnType()).getActualTypeArguments()[1];
            Map<String, Object> resultMap = new HashMap<>();
            for (String entryKey : section.getKeys(false)) {
                ConfigurationSection entrySection = section.getConfigurationSection(entryKey);
                Object deserializedValue = Proxy.newProxyInstance(
                        valueType.getClassLoader(),
                        new Class<?>[]{valueType},
                        new ConfigInvocationHandler(valueType, entrySection, configFile)
                );
                resultMap.put(entryKey, deserializedValue);
            }
            return resultMap;
        }


        private Object handleList(String key, Method method) throws Exception {
            List<?> rawList = configSection.getList(key);
            if (rawList == null) return new ArrayList<>();

            Class<?> itemType = (Class<?>) ((java.lang.reflect.ParameterizedType) method.getGenericReturnType()).getActualTypeArguments()[0];
            return rawList.stream()
                    .map(item -> {
                        try {
                            ConfigurationSection itemSection = configSection.getConfigurationSection(item.toString());
                            return Proxy.newProxyInstance(
                                    itemType.getClassLoader(),
                                    new Class<?>[]{itemType},
                                    new ConfigInvocationHandler(itemType, itemSection, configFile)
                            );
                        } catch (Exception e) {
                            throw new RuntimeException("Failed to deserialize list item", e);
                        }
                    })
                    .collect(Collectors.toList());
        }


        private Object getDefaultValue(Method method) {
            Class<?> returnType = method.getReturnType();

            // Стандартные типы
            if (returnType == String.class) return "";
            if (returnType == int.class) return 0;
            if (returnType == List.class) return new ArrayList<>();
            if (returnType == Map.class) return new HashMap<>();

            // Если тип является классом, аннотированным @Config (например, SettingsConfig или ItemConfig)
            if (returnType.isInterface() && returnType.isAnnotationPresent(Config.class)) {
                try {
                    // Создаем новый экземпляр интерфейса с помощью прокси
                    return Proxy.newProxyInstance(
                            returnType.getClassLoader(),
                            new Class<?>[]{returnType},
                            new ConfigInvocationHandler(returnType, configSection.getConfigurationSection(method.getName().toLowerCase()), configFile)
                    );
                } catch (Exception e) {
                    throw new RuntimeException("Failed to instantiate config interface", e);
                }
            }

            // Если тип не поддерживается
            throw new IllegalArgumentException("Unsupported return type: " + returnType.getName());
        }

        private void saveConfig() {
            try {
                yamlConfig.save(configFile);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

}
