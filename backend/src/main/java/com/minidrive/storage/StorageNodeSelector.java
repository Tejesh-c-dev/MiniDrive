package com.minidrive.storage;
import java.util.Collection;
public interface StorageNodeSelector { String select(String objectKey, Collection<String> eligibleNodeIds); }
