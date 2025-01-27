package me.fallen.configurationlibrary;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.*;
import java.util.stream.Collectors;

public class ConfigManager {

    private final File configDirectory;
    private final Map<Class<?>, Object> cache = new HashMap<>();
    private boolean changesMade = false;

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
        private boolean changesMade = false;

        public ConfigInvocationHandler(Class<?> configClass, ConfigurationSection configSection, File configFile) {
            this.configClass = configClass;
            if (!configFile.exists()) {
                try {
                    configFile.createNewFile();
                } catch (IOException e) {
                    throw new RuntimeException("Could not create config file: " + configFile.getAbsolutePath(), e);
                }
            }
            yamlConfig = YamlConfiguration.loadConfiguration(configFile);
            if (configSection == null) {
                this.configSection = yamlConfig.createSection(configClass.getSimpleName());
            } else {
                this.configSection = configSection;
            }
            this.configFile = configFile;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            String key = method.getName().replaceAll("([a-z])([A-Z])", "$1-$2").toLowerCase();

            if (method.isAnnotationPresent(Node.class)) {
                return handleNodeMethod(method, key);
            }

            if (method.getName().startsWith("set") && args != null && args.length == 1) {
                configSection.set(key, args[0]);
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
                return handleSerialization(method, key, args);
            }

            return value;
        }

        private Object handleNodeMethod(Method method, String key) throws Exception {
            Class<?> returnType = method.getReturnType();
            if (Map.class.isAssignableFrom(returnType)) {
                return handleMap(key, method);
            } else if (List.class.isAssignableFrom(returnType)) {
                return handleList(key, method);
            }

            // Добавим проверку для configSection на null
            ConfigurationSection section = configSection.getConfigurationSection(key);
            if (section == null) {
                section = configSection.createSection(key);  // Если секция не существует, создаем новую
            }

            return Proxy.newProxyInstance(
                    returnType.getClassLoader(),
                    new Class<?>[]{returnType},
                    new ConfigInvocationHandler(returnType, section, configFile)
            );
        }

        private Object handleMap(String key, Method method) throws Exception {
            ConfigurationSection section = configSection.getConfigurationSection(key);
            if (section == null) return new HashMap<>();

            java.lang.reflect.Type returnType = method.getGenericReturnType();
            if (returnType instanceof java.lang.reflect.ParameterizedType) {
                java.lang.reflect.ParameterizedType paramType = (java.lang.reflect.ParameterizedType) returnType;
                Class<?> valueType = (Class<?>) paramType.getActualTypeArguments()[1];

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
            } else {
                throw new IllegalArgumentException("Expected a parameterized Map type for method " + method.getName());
            }
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

        private Object handleSerialization(Method method, String key, Object[] args) throws Exception {
            Class<? extends SerializerInterface<?>> serializerClass = method.getAnnotation(Serializer.class).value();
            SerializerInterface<?> serializer = serializerClass.getDeclaredConstructor().newInstance();

            if (args != null && args.length == 1) {
                Object serializedValue = ((SerializerInterface<Object>) serializer).serialize(args[0]);
                yamlConfig.set(key, serializedValue);
                saveConfig();
                return null;
            }

            Object rawValue = yamlConfig.get(key);
            return serializer.deserialize(rawValue);
        }

        private Object getDefaultValue(Method method) {
            Class<?> returnType = method.getReturnType();

            if (returnType == String.class) return "";
            if (returnType == int.class) return 0;
            if (returnType == List.class) return new ArrayList<>();
            if (returnType == Map.class) return new HashMap<>();

            if (returnType.isInterface() && returnType.isAnnotationPresent(Config.class)) {
                return Proxy.newProxyInstance(
                        returnType.getClassLoader(),
                        new Class<?>[]{returnType},
                        new ConfigInvocationHandler(returnType, configSection.getConfigurationSection(method.getName().toLowerCase()), configFile)
                );
            }

            throw new IllegalArgumentException("Unsupported return type: " + returnType.getName());
        }

        private void saveConfig() {
            if (changesMade) {
                try {
                    Map<String, Object> serializableData = new HashMap<>();
                    for (String key : configSection.getKeys(false)) {
                        Object value = configSection.get(key);
                        if (value instanceof Proxy) {
                            value = extractProxyData(value);
                        }
                        serializableData.put(key, value);
                    }

                    yamlConfig.set(configSection.getCurrentPath(), serializableData);
                    yamlConfig.save(configFile);
                    changesMade = false;
                } catch (IOException e) {
                    e.printStackTrace();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
        }

        private Object extractProxyData(Object proxy) throws Exception {
            Class<?> proxyClass = proxy.getClass();
            Method[] methods = proxyClass.getDeclaredMethods();
            Map<String, Object> data = new HashMap<>();
            for (Method method : methods) {
                if (method.getName().startsWith("get")) {
                    String key = method.getName().substring(3).toLowerCase();
                    Object value = method.invoke(proxy);
                    data.put(key, value);
                }
            }
            return data;
        }
    }

}

