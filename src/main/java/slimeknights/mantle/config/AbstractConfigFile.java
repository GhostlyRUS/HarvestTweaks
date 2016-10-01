package slimeknights.mantle.config;

import com.google.common.reflect.TypeToken;

import net.minecraft.block.Block;
import net.minecraft.item.Item;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fml.common.registry.GameData;
import net.minecraftforge.fml.common.registry.IForgeRegistry;
import net.minecraftforge.fml.common.registry.IForgeRegistryEntry;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Serializable;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import slimeknights.mantle.configurate.ConfigurationNode;
import slimeknights.mantle.configurate.ConfigurationOptions;
import slimeknights.mantle.configurate.commented.CommentedConfigurationNode;
import slimeknights.mantle.configurate.hocon.HoconConfigurationLoader;
import slimeknights.mantle.configurate.loader.AtomicFiles;
import slimeknights.mantle.configurate.loader.ConfigurationLoader;
import slimeknights.mantle.configurate.objectmapping.ObjectMappingException;
import slimeknights.mantle.configurate.objectmapping.serialize.TypeSerializer;
import slimeknights.mantle.configurate.objectmapping.serialize.TypeSerializers;

public abstract class AbstractConfigFile implements Serializable {

  private static boolean initialized = false;

  private final File file;
  private final ConfigurationLoader<CommentedConfigurationNode> loader;
  private boolean needsSaving = false;

  public AbstractConfigFile(File configFolder, String name) {
    this(new File(configFolder, name + ".cfg"));
  }

  public AbstractConfigFile(File configFile) {
    configFile.getParentFile().mkdirs();
    file = configFile;
    loader = HoconConfigurationLoader.builder().setFile(file).build();
  }

  public CommentedConfigurationNode load() throws IOException {
    return loader.load(ConfigurationOptions.defaults().setShouldCopyDefaults(true));
  }

  public void save(ConfigurationNode node) throws IOException {
    loader.save(node);
  }

  public String getName() {
    return file.getName();
  }

  public abstract void insertDefaults();


  public AbstractConfigFile loadFromPacket(byte[] packetData) {
    ConfigurationLoader<CommentedConfigurationNode> packetDataLoader = HoconConfigurationLoader
        .builder()
        .setSource(() -> new BufferedReader(new InputStreamReader(new ByteArrayInputStream(packetData))))
        .setSink(AtomicFiles.createAtomicWriterFactory(file.toPath(), StandardCharsets.UTF_8))
        .build();

    try {
      CommentedConfigurationNode node = packetDataLoader.load(ConfigurationOptions.defaults().setShouldCopyDefaults(true));

      try {
        return node.getValue(TypeToken.of(this.getClass()));
      } catch(ObjectMappingException e) {
        e.printStackTrace();
      }
    } catch(IOException e) {
      e.printStackTrace();
    }
    return null;
  }

  public static List<Field> getAllFields(List<Field> fields, Class<?> type) {
    fields.addAll(Arrays.asList(type.getDeclaredFields()));

    if(type.getSuperclass() != null && AbstractConfigFile.class.isAssignableFrom(type.getSuperclass())) {
      fields = getAllFields(fields, type.getSuperclass());
    }

    return fields;
  }

  public byte[] getPacketData() {
    try {
      return Files.readAllBytes(file.toPath());
    } catch(IOException e) {
      e.printStackTrace();
    }
    return null;
  }

  public boolean sync(AbstractConfigFile other) {
    if(other.getClass() != this.getClass()) {
      return false;
    }

    List<Field> fieldsToProcess = new ArrayList<>();
    getAllFields(fieldsToProcess, this.getClass());

    for(Field field : fieldsToProcess) {
      try {
        if(!field.isAccessible()) {
          field.setAccessible(true);
        }
        Object original = field.get(this);
        Object remote = field.get(other);

        if(!original.equals(remote)) {
          field.set(this, remote);
          setNeedsSaving();
        }
      } catch(IllegalAccessException e) {
        e.printStackTrace();
      }
    }

    return needsSaving();
  }

  public void setNeedsSaving() {
    needsSaving = true;
  }

  public boolean needsSaving() {
    return needsSaving;
  }

  public void clearNeedsSaving() {
    needsSaving = false;
  }

  public static void init() {
    if(initialized) {
      return;
    }

    // item and block serializer/deserializer
    TypeSerializers.getDefaultSerializers().registerType(TypeToken.of(Block.class), new RegistrySerializer<Block>() {
      @Override
      IForgeRegistry<Block> getRegistry() {
        return GameData.getBlockRegistry();
      }
    });

    TypeSerializers.getDefaultSerializers().registerType(TypeToken.of(Item.class), new RegistrySerializer<Item>() {
      @Override
      IForgeRegistry<Item> getRegistry() {
        return GameData.getItemRegistry();
      }
    });

    TypeSerializers.getDefaultSerializers().registerType(TypeToken.of(BlockMeta.class), BlockMeta.SERIALIZER);

    initialized = true;
  }

  private static abstract class RegistrySerializer<T extends IForgeRegistryEntry<T>> implements TypeSerializer<T> {

    // done at runtime so registry changes from joining servers take effect
    abstract IForgeRegistry<T> getRegistry();

    @Override
    public T deserialize(TypeToken<?> typeToken, ConfigurationNode configurationNode) throws ObjectMappingException {
      return getRegistry().getValue(new ResourceLocation(configurationNode.getString()));
    }

    @Override
    public void serialize(TypeToken<?> typeToken, T t, ConfigurationNode configurationNode)
        throws ObjectMappingException {
      configurationNode.setValue(t.getRegistryName());
    }
  }
}
