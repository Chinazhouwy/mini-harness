package com.chinazhouwy.miniharness.runtime.workflow;

import com.chinazhouwy.miniharness.runtime.core.HarnessException;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** 节点类型注册表：编译期校验与执行期分发共用。 */
public class NodeTypeRegistry {

    private final Map<String, NodeHandler> handlers = new ConcurrentHashMap<>();

    public NodeTypeRegistry register(NodeHandler handler) {
        NodeHandler existing = handlers.putIfAbsent(handler.type(), handler);
        if (existing != null) {
            throw new HarnessException("HANDLER_DUP", "节点处理器类型重复注册: " + handler.type());
        }
        return this;
    }

    public NodeHandler require(String type) {
        NodeHandler handler = handlers.get(type);
        if (handler == null) {
            throw new HarnessException("HANDLER_NOT_FOUND", "节点类型未注册: " + type);
        }
        return handler;
    }

    public boolean has(String type) {
        return handlers.containsKey(type);
    }
}
