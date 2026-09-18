package com.easymall.service.Impl;

import java.util.Date;
import java.util.List;

import com.easymall.entity.constants.Constants;
import com.easymall.entity.po.ProductSku;
import com.easymall.entity.query.ProductSkuQuery;
import com.easymall.exception.BusinessException;
import com.easymall.mappers.ProductSkuMapper;
import jakarta.annotation.Resource;

import org.springframework.stereotype.Service;

import com.easymall.entity.enums.PageSize;
import com.easymall.entity.query.ProductCartQuery;
import com.easymall.entity.po.ProductCart;
import com.easymall.entity.vo.PaginationResultVO;
import com.easymall.entity.query.SimplePage;
import com.easymall.mappers.ProductCartMapper;
import com.easymall.service.ProductCartService;
import com.easymall.utils.StringTools;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;


/**
 * 购物车 业务接口实现
 */
@Service("productCartService")
public class ProductCartServiceImpl implements ProductCartService {

	@Resource
	private ProductCartMapper<ProductCart, ProductCartQuery> productCartMapper;

	@Resource
	private ProductSkuMapper<ProductSku, ProductSkuQuery> productSkuMapper;

	/**
	 * 根据条件查询列表
	 */
	@Override
	public List<ProductCart> findListByParam(ProductCartQuery param) {
		return this.productCartMapper.selectList(param);
	}

	/**
	 * 根据条件查询列表
	 */
	@Override
	public Integer findCountByParam(ProductCartQuery param) {
		return this.productCartMapper.selectCount(param);
	}

	/**
	 * 分页查询方法
	 */
	@Override
	public PaginationResultVO<ProductCart> findListByPage(ProductCartQuery param) {
		int count = this.findCountByParam(param);
		int pageSize = param.getPageSize() == null ? PageSize.SIZE15.getSize() : param.getPageSize();

		SimplePage page = new SimplePage(param.getPageNo(), count, pageSize);
		param.setSimplePage(page);
		List<ProductCart> list = this.findListByParam(param);
		PaginationResultVO<ProductCart> result = new PaginationResultVO(count, page.getPageSize(), page.getPageNo(), page.getPageTotal(), list);
		return result;
	}

	/**
	 * 新增
	 */
	@Override
	public Integer add(ProductCart bean) {
		return this.productCartMapper.insert(bean);
	}

	/**
	 * 批量新增
	 */
	@Override
	public Integer addBatch(List<ProductCart> listBean) {
		if (listBean == null || listBean.isEmpty()) {
			return 0;
		}
		return this.productCartMapper.insertBatch(listBean);
	}

	/**
	 * 批量新增或者修改
	 */
	@Override
	public Integer addOrUpdateBatch(List<ProductCart> listBean) {
		if (listBean == null || listBean.isEmpty()) {
			return 0;
		}
		return this.productCartMapper.insertOrUpdateBatch(listBean);
	}

	/**
	 * 多条件更新
	 */
	@Override
	public Integer updateByParam(ProductCart bean, ProductCartQuery param) {
		StringTools.checkParam(param);
		return this.productCartMapper.updateByParam(bean, param);
	}

	/**
	 * 多条件删除
	 */
	@Override
	public Integer deleteByParam(ProductCartQuery param) {
		StringTools.checkParam(param);
		return this.productCartMapper.deleteByParam(param);
	}

	/**
	 * 根据CartId获取对象
	 */
	@Override
	public ProductCart getProductCartByCartId(String cartId) {
		return this.productCartMapper.selectByCartId(cartId);
	}

	/**
	 * 根据CartId修改
	 */
	@Override
	public Integer updateProductCartByCartId(ProductCart bean, String cartId) {
		return this.productCartMapper.updateByCartId(bean, cartId);
	}

	/**
	 * 根据CartId删除
	 */
	@Override
	public Integer deleteProductCartByCartId(String cartId) {
		return this.productCartMapper.deleteByCartId(cartId);
	}

	/**
	 * 根据ProductIdAndPropertyValueIdHashAndUserId获取对象
	 */
	@Override
	public ProductCart getProductCartByProductIdAndPropertyValueIdHashAndUserId(String productId, String propertyValueIdHash, String userId) {
		return this.productCartMapper.selectByProductIdAndPropertyValueIdHashAndUserId(productId, propertyValueIdHash, userId);
	}

	/**
	 * 根据ProductIdAndPropertyValueIdHashAndUserId修改
	 */
	@Override
	public Integer updateProductCartByProductIdAndPropertyValueIdHashAndUserId(ProductCart bean, String productId, String propertyValueIdHash, String userId) {
		return this.productCartMapper.updateByProductIdAndPropertyValueIdHashAndUserId(bean, productId, propertyValueIdHash, userId);
	}

	/**
	 * 根据ProductIdAndPropertyValueIdHashAndUserId删除
	 */
	@Override
	public Integer deleteProductCartByProductIdAndPropertyValueIdHashAndUserId(String productId, String propertyValueIdHash, String userId) {
		return this.productCartMapper.deleteByProductIdAndPropertyValueIdHashAndUserId(productId, propertyValueIdHash, userId);
	}

	/**
	 * 商品加入购物车
	 * @param cart 商品购物车对象
	 */
	@Override
	@Transactional(rollbackFor = Exception.class)
	public void add2Cart(ProductCart cart) {
		Date curDate = new Date();

		ProductCartQuery productCartQuery = new ProductCartQuery();
		productCartQuery.setProductId(cart.getProductId());
		productCartQuery.setUserId(cart.getUserId());

		ProductCart productCart = new ProductCart();
		productCart.setLastUpdateTime(new Date(curDate.getTime()-1000));
		this.productCartMapper.updateByParam(productCart,productCartQuery);

		String propertyValueIdHash = StringTools.encodeByMD5(cart.getPropertyValueIdHash());

		//判断添加的商品是否存在
		ProductSku sku = this.productSkuMapper.selectByProductIdAndPropertyValueIdHash(cart.getProductId(), propertyValueIdHash);
		if (sku == null) {
			throw new BusinessException("商品不存在");
		}

		// 判断商品是否是第一次添加进购物车
		ProductCart dbCart = this.productCartMapper.selectByProductIdAndPropertyValueIdHashAndUserId(cart.getProductId(),
				propertyValueIdHash, cart.getUserId());
		if(dbCart != null) {
			String cartId = StringTools.getRandomString(Constants.LENGTH_15);
			cart.setCartId(cartId);
			cart.setPropertyValueIdHash(propertyValueIdHash);
			cart.setLastUpdateTime(curDate);
			cart.setCreateTime(curDate);
			this.add(cart);
		}else {
			this.productCartMapper.updateCartBuyCount(dbCart.getCartId(),cart.getBuyCount());
		}
	}
}