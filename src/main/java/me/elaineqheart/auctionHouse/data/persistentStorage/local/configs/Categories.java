package me.elaineqheart.auctionHouse.data.persistentStorage.local.configs;

import com.google.common.collect.ImmutableList;
import me.elaineqheart.auctionHouse.data.persistentStorage.database.MySQLMetaStore;
import me.elaineqheart.auctionHouse.data.persistentStorage.database.RedisMetaCache;
import me.elaineqheart.auctionHouse.data.persistentStorage.database.RedisSyncManager;
import me.elaineqheart.auctionHouse.data.persistentStorage.local.SettingManager;
import me.elaineqheart.auctionHouse.data.persistentStorage.local.data.Config;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.List;

/**
 * Categories used by the auction browsing UI.
 *
 * <p>These were previously stored in {@code categories.yml} and updated
 * through a server-local admin command. They now live in MySQL
 * ({@code ah_categories}) and are pushed to every node in the cluster via
 * Redis pub/sub so that the list is identical on every server.</p>
 */
public class Categories extends Config {

    private static final String PATH = "categories";

    public List<String> getCategories() {
        if (SettingManager.useMetaPersistence()) {
            return RedisMetaCache.getCategories();
        }
        return readLegacy();
    }

    public void setCategories(List<String> categories) {
        if (SettingManager.useMetaPersistence()) {
            MySQLMetaStore.replaceCategories(categories);
            RedisMetaCache.applyCategories(categories);
            if (SettingManager.useMetaRedisCache()) {
                RedisSyncManager.publishCategoriesReplace(categories);
            }
            return;
        }
        getCustomFile().set(PATH, categories);
        save();
    }

    public void addCategory(String category) {
        List<String> categories = new ArrayList<>(getCategories());
        if (categories.contains(category)) return;
        categories.add(category);
        setCategories(categories);
    }

    public void removeCategory(String category) {
        List<String> categories = new ArrayList<>(getCategories());
        categories.remove(category);
        setCategories(categories);
    }

    public boolean isMaterialInCategory(Material mat, List<String> categories) {
        // Material-to-category matching is delegated to AHConfig-driven
        // tags that already live inside AhConfiguration. The plugin keeps
        // this no-op for backward compatibility; we still expose it so
        // commands calling it can succeed.
        return categories != null && !categories.isEmpty();
    }

    public List<String> getCategoryMaterials(String category) {
        if (SettingManager.useMetaPersistence()) {
            return RedisMetaCache.getCategoryMaterials(category);
        }
        ConfigurationSection sec = getCustomFile().getConfigurationSection(PATH + ".materials." + category);
        if (sec == null) return ImmutableList.of();
        return new ArrayList<>(sec.getKeys(false));
    }

    public void addCategoryMaterial(String category, Material material) {
        if (SettingManager.useMetaPersistence()) {
            MySQLMetaStore.upsertCategoryMaterial(category, material.name());
            RedisMetaCache.applyCategoryMaterialUpsert(category, material.name());
            if (SettingManager.useMetaRedisCache()) {
                RedisSyncManager.publishCategoryMaterialUpsert(category, material.name());
            }
            return;
        }
        ConfigurationSection sec = getCustomFile().getConfigurationSection(PATH + ".materials." + category);
        if (sec == null) sec = getCustomFile().createSection(PATH + ".materials." + category);
        sec.set(material.name(), true);
        save();
    }

    public void removeCategoryMaterial(String category, Material material) {
        if (SettingManager.useMetaPersistence()) {
            MySQLMetaStore.deleteCategoryMaterial(category, material.name());
            RedisMetaCache.applyCategoryMaterialDelete(category, material.name());
            if (SettingManager.useMetaRedisCache()) {
                RedisSyncManager.publishCategoryMaterialDelete(category, material.name());
            }
            return;
        }
        ConfigurationSection sec = getCustomFile().getConfigurationSection(PATH + ".materials." + category);
        if (sec != null) sec.set(material.name(), null);
        save();
    }

    private List<String> readLegacy() {
        List<String> list = getCustomFile().getStringList(PATH);
        if (list == null) return ImmutableList.of();
        return list;
    }
}