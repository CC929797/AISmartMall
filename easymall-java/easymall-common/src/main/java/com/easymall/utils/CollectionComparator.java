package com.easymall.utils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

public class CollectionComparator<T> {
    public static class DiffResult<T> {
        public List<T> addList = new ArrayList<>();
        public List<T> updateList = new ArrayList<>();
        public List<T> deleteList = new ArrayList<>();
    }

    public DiffResult<T> compare(Collection<T> newList, Collection<T> oldList, Function<T,String> idExtractor) {
        DiffResult<T> result = new DiffResult<>();
        Map<String, T> oldMap = oldList.stream().collect(Collectors.toMap(idExtractor, Function.identity()));
        Map<String, T> newMap = newList.stream().collect(Collectors.toMap(idExtractor, Function.identity()));

        //思路：根据参数里修改后的集合与数据原来的集合数据进行比较来确定
        //找出新增的修改的
        //newList:参数里修改后的集合 oldList:数据库原来的集合
        //如果newList中数据在oldList数据库里没有此数据，代表需要插入新的数据，如果有则是直接修改即可
        //如果oldList中的数据在newList集合当中没有，则代表已经被删除了
        for (Map.Entry<String, T> entry : newMap.entrySet()) {
            String key = entry.getKey();
            T newObject = entry.getValue();
            if (!oldMap.containsKey(key)) {
                result.addList.add(newObject);
            }else {
                result.updateList.add(newObject);
            }
        }

        //找出删除的
        for (Map.Entry<String, T> entry : oldMap.entrySet()) {
            if (!newMap.containsKey(entry.getKey())) {
                result.deleteList.add(entry.getValue());
            }
        }

        return result;
    }
}
