package com.jd.joyqueue.nsr.composition.config;

import com.jd.joyqueue.toolkit.config.PropertySupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * CompositionConfig
 * author: gaohaoxiang
 * date: 2019/8/12
 */
public class CompositionConfig {

    protected static final Logger logger = LoggerFactory.getLogger(CompositionConfig.class);

    private PropertySupplier propertySupplier;

    public CompositionConfig(PropertySupplier propertySupplier) {
        this.propertySupplier = propertySupplier;
    }

    public String getReadSource() {
        return PropertySupplier.getValue(propertySupplier, CompositionConfigKey.READ_SOURCE);
    }

    public String getWriteSource() {
        return PropertySupplier.getValue(propertySupplier, CompositionConfigKey.WRITE_SOURCE);
    }

    public boolean isWriteAll() {
        return getWriteSource().equalsIgnoreCase("all");
    }

    public boolean isReadIgnite() {
        return getReadSource().equalsIgnoreCase("ignite");
    }

    public boolean isWriteIgnite() {
        return getWriteSource().equalsIgnoreCase("ignite");
    }

    public boolean isReadJournalkeeper() {
        return getReadSource().equalsIgnoreCase("journalkeeper");
    }

    public boolean isWriteJournalkeeper() {
        return getReadSource().equalsIgnoreCase("journalkeeper");
    }
}