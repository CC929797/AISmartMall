package com.easymall.service.Impl;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.easymall.entity.constants.Constants;
import com.easymall.entity.enums.ProductStatusEnum;
import com.easymall.entity.po.ProductInfo;
import com.easymall.entity.po.ProductPropertyValue;
import com.easymall.entity.po.ProductSku;
import com.easymall.entity.query.*;
import com.easymall.entity.vo.ProductSkuProperDataVO;
import com.easymall.entity.vo.ProductSkuVO;
import com.easymall.exception.BusinessException;
import com.easymall.mappers.ProductInfoMapper;
import com.easymall.mappers.ProductPropertyValueMapper;
import com.easymall.mappers.ProductSkuMapper;
import jakarta.annotation.Resource;

import org.springframework.stereotype.Service;

import com.easymall.entity.enums.PageSize;
import com.easymall.entity.po.ProductCart;
import com.easymall.entity.vo.PaginationResultVO;
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
	@Resource
	private ProductInfoMapper<ProductInfo, ProductInfoQuery> productInfoMapper;
	@Resource
	private ProductPropertyValueMapper<ProductPropertyValue, ProductPropertyValueQuery> productPropertyValueMapper;

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

		String propertyValueIdHash = StringTools.encodeByMD5(cart.getPropertyValueIds());

		//判断添加的商品是否存在
		ProductSku sku = this.productSkuMapper.selectByProductIdAndPropertyValueIdHash(cart.getProductId(), propertyValueIdHash);
		if (sku == null) {
			throw new BusinessException("商品不存在");
		}

		// 判断商品是否是第一次添加进购物车
		ProductCart dbCart = this.productCartMapper.selectByProductIdAndPropertyValueIdHashAndUserId(cart.getProductId(),
				propertyValueIdHash, cart.getUserId());
		if(dbCart == null) {
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

	/**
	 * 分页查询购物车商品信息
	 * @param query
	 * @return
	 */
	@Override
	public PaginationResultVO loadProductCart(ProductCartQuery query) {
		// 先进行分页查询到购物车当中的基本信息、查询到的总数等
		PaginationResultVO<ProductCart> resultVO = this.findListByPage(query);
		//获取到购物车的商品基本信息
		List<ProductCart> list = resultVO.getList();
		if (list.isEmpty()) {
			return new PaginationResultVO<>(0,query.getPageSize(),query.getPageNo(),0,new ArrayList<>());
		}

		//查询商品信息
		List<String> productIdList = list.stream().map(ProductCart::getProductId).collect(Collectors.toList());
		ProductInfoQuery productInfoQuery = new ProductInfoQuery();
		productInfoQuery.setProductIdList(productIdList);
		List<ProductInfo> productInfoList = this.productInfoMapper.selectList(productInfoQuery);
		Map<String, ProductInfo> productInfoMap = productInfoList.stream().collect(Collectors.toMap(ProductInfo::getProductId, Function.identity(),
				(data1,data2) -> data2));
		
		//查询商品属性信息
		ProductPropertyValueQuery propertyValueQuery = new ProductPropertyValueQuery();
		propertyValueQuery.setProductIdList(productIdList);
		List<ProductPropertyValue> propertyValueList = this.productPropertyValueMapper.selectList(propertyValueQuery);
		Map<String, ProductPropertyValue> propertyValueMap = propertyValueList.stream().collect(Collectors.toMap(item -> item.getProductId() + item.getPropertyValueId(), Function.identity(),
				(data1,data2) -> data2));

		//查询商品Sku信息
		ProductSkuQuery skuQuery = new ProductSkuQuery();
		skuQuery.setProductIdList(productIdList);
		List<ProductSku> skuList = this.productSkuMapper.selectList(skuQuery);
		Map<String, ProductSku> skuMap = skuList.stream().collect(Collectors.toMap(item -> item.getProductId() + item.getPropertyValueIds(), Function.identity(),
				(data1,data2) -> data2));

		// 新增最终的展示所有商品的 ProductSkuVO 的集合
		List<ProductSkuVO> productSkuVOList	= new ArrayList<>();

		// 遍历购物车中的商品，遍历每一个商品并进行封装信息到最后的 ProductSkuVO
		for (ProductCart cart : list) {
			ProductSkuVO productSkuVO = new ProductSkuVO();
			productSkuVO.setCartId(cart.getCartId());
			String propertyValueIds = cart.getPropertyValueIds();
			String[] propertyValueIdArray = propertyValueIds.split("-");

			List<ProductSkuProperDataVO> properData = new ArrayList<>();

			String cover = null;
			for (String propertyValueId : propertyValueIdArray) {
				ProductSkuProperDataVO properDataVO = new ProductSkuProperDataVO();
				ProductPropertyValue propertyValue = propertyValueMap.get(cart.getProductId() + propertyValueId);
				if (propertyValue == null) {
					continue;
				}
				properDataVO.setPropertyName(propertyValue.getPropertyName());
				properDataVO.setPropertyValue(propertyValue.getPropertyValue());
				properData.add(properDataVO);

				if(cover == null && !StringTools.isEmpty(propertyValue.getPropertyCover())) {
					cover = propertyValue.getPropertyCover();
				}
			}

			productSkuVO.setPropertyData(properData);

			ProductInfo productInfo = productInfoMap.get(cart.getProductId());

			ProductSku productSku = skuMap.get(cart.getProductId() + cart.getPropertyValueIds());

			// 如果属性值的图片没有，则采用展示的是商品的主图的第一张照片
			cover = cover == null ? productInfo.getCover().split(",")[0] : cover;

			//封装ProductSkuVO属性
			productSkuVO.setProductId(productInfo.getProductId());
			productSkuVO.setProductName(productInfo.getProductName());

			productSkuVO.setPrice(productSku.getPrice());
			productSkuVO.setStock(productSku.getStock());

			productSkuVO.setPropertyValueIds(cart.getPropertyValueIds());
			productSkuVO.setPropertyValueIdHash(cart.getPropertyValueIdHash());
			productSkuVO.setBuyCount(cart.getBuyCount());
			productSkuVO.setProductCover(cover);

			productSkuVO.setProductOnSale(ProductStatusEnum.ON_SALE.getStatus().equals(productInfo.getStatus()));

			productSkuVOList.add(productSkuVO);
		}

		// 封装 ProductSkuVO 到最终的分页类 PaginationResult 中
		return new PaginationResultVO<>(resultVO.getTotalCount(),query.getPageSize(),query.getPageNo(),resultVO.getPageTotal(),productSkuVOList);
	}
}