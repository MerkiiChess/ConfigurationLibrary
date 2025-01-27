package me.fallen.configurationlibrary;

public interface SerializerInterface<T> {
    T deserialize(Object value);
    Object serialize(T value);
}
