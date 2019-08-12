package com.jd.joyqueue.nsr.composition.config;

import com.jd.joyqueue.toolkit.config.PropertyDef;

/**
 * CompositionConfigKey
 * author: gaohaoxiang
 * date: 2019/8/12
 */
public enum CompositionConfigKey implements PropertyDef {

    // 读数据源
    READ_SOURCE("nsr.composition.read.source", "ignite", PropertyDef.Type.STRING),

    // 写数据源
    WRITE_SOURCE("nsr.composition.write.source", "all", PropertyDef.Type.STRING),

    ;

    private String name;
    private Object value;
    private Type type;

    CompositionConfigKey(String name, Object value, Type type) {
        this.name = name;
        this.value = value;
        this.type = type;
    }

    public String getName() {
        return name;
    }

    public Object getValue() {
        return value;
    }

    public Type getType() {
        return type;
    }
}
