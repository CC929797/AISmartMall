package com.easymall.mappers;

import org.apache.ibatis.annotations.Param;

/**
 * 购物车 数据库操作接口
 */
public interface ProductCartMapper<T,P> extends BaseMapper<T,P> {

	/**
	 * 根据CartId更新
	 */
	 Integer updateByCartId(@Param("bean") T t,@Param("cartId") String cartId);


	/**
	 * 根据CartId删除
	 */
	 Integer deleteByCartId(@Param("cartId") String cartId);


	/**
	 * 根据CartId获取对象
	 */
	 T selectByCartId(@Param("cartId") String cartId);


	/**
	 * 根据ProductIdAndPropertyValueIdHashAndUserId更新
	 */
	 Integer updateByProductIdAndPropertyValueIdHashAndUserId(@Param("bean") T t,@Param("productId") String productId,@Param("propertyValueIdHash") String propertyValueIdHash,@Param("userId") String userId);


	/**
	 * 根据ProductIdAndPropertyValueIdHashAndUserId删除
	 */
	 Integer deleteByProductIdAndPropertyValueIdHashAndUserId(@Param("productId") String productId,@Param("propertyValueIdHash") String propertyValueIdHash,@Param("userId") String userId);


	/**
	 * 根据ProductIdAndPropertyValueIdHashAndUserId获取对象
	 */
	 T selectByProductIdAndPropertyValueIdHashAndUserId(@Param("productId") String productId,@Param("propertyValueIdHash") String propertyValueIdHash,@Param("userId") String userId);

	/**
	 * 更改购物车中的商品数量
	 * @param cartId
	 * @param buyCount
	 */
	 void updateCartBuyCount(@Param("cartId") String cartId,@Param("buyCount") Integer buyCount);
}
