package me.elaineqheart.auctionHouse.data.persistentStorage.local.data;

import com.google.common.base.Charsets;
import me.elaineqheart.auctionHouse.AuctionHouse;
import me.elaineqheart.auctionHouse.data.persistentStorage.local.SettingManager;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

/**
 * Base class for the per-server YAML files that the plugin still ships for
 * backwards compatibility and for fallback (pure JSON) installs.
 *
 * <p>Subclasses that have been migrated to the central MySQL+Redis pipeline
 * no longer rely on this for <em>authoritative</em> storage; the YAML file
 * becomes a one-time seed that is hydrated into MySQL on first start-up and
 * never written again. The {@link #setup(String, boolean, String)} method
 * still creates the file so administrators can keep tweaking the GUI layout
 * or messages locally, but {@link #save()} is a no-op for migrated configs
 * (they are owned by {@code MySQLMetaStore} and {@code RedisMetaCache}).</p>
 */
public class Config {

    private File file;
    private FileConfiguration customFile;
    private String fileName;

    public void setup(String fileName, boolean copyDefaults, String parent){
        this.fileName = fileName;
        file = new File(AuctionHouse.getInstance().getDataFolder() + parent,  fileName);

        if (!file.exists()){
            try{
                file.getParentFile().mkdirs();
                file.createNewFile();
            }catch (IOException e){
                //uwu
            }
        }
        if(ConfigManager.backwardsCompatibility() && !parent.isEmpty()) backwardsCompatibility(fileName, parent);
        customFile = YamlConfiguration.loadConfiguration(file);

        if(copyDefaults) {
            final InputStream defConfigStream = AuctionHouse.getInstance().getResource(fileName);
            if (defConfigStream != null) {
                customFile.setDefaults(YamlConfiguration.loadConfiguration(new InputStreamReader(defConfigStream, Charsets.UTF_8)));
                customFile.options().copyDefaults(true);
            }
        }

        save();
        setup();
    }

    public void setup() {} //overwrite method

    public FileConfiguration getCustomFile(){
        return customFile;
    }

    public File getFile() {
        return file;
    }

    /**
     * Persist the in-memory YAML representation. For configs that have been
     * migrated to the MySQL+Redis pipeline we keep the file in sync as a
     * convenience for operators who edit YAML by hand; otherwise this is a
     * no-op once {@link SettingManager#useMetaPersistence()} is true and
     * the data has been hydrated at least once.
     */
    public void save(){
        if (isReadOnly()) return;
        try {
            customFile.save(file);
        }catch (IOException e){
            AuctionHouse.getInstance().getLogger().severe("Couldn't save " + fileName + " file");
        }
    }

    public void reload(){
        customFile = YamlConfiguration.loadConfiguration(file);
        reloadChild();
    }

    public void reloadChild() {}

    /**
     * Hook for subclasses: when true, {@link #save()} should not write the
     * YAML file because the data now lives in MySQL/Redis. Subclasses opt
     * in by overriding and returning true after their first successful
     * hydration.
     */
    protected boolean isReadOnly() {
        return false;
    }

    private void backwardsCompatibility(String fileName, String parent) {
        File file = new File(AuctionHouse.getInstance().getDataFolder().getAbsolutePath() + parent + "/" + fileName);
        File old = new File(AuctionHouse.getInstance().getDataFolder().getAbsolutePath() + "/" + fileName);
        if (old.exists()) {

            try {
                Files.copy(old.getAbsoluteFile().toPath(), file.getAbsoluteFile().toPath(), StandardCopyOption.REPLACE_EXISTING);
                old.delete();
            } catch (IOException ignored) {}
        }
    }

}