package com.easymall.service.Impl;

import java.util.*;
import java.util.stream.Collectors;

import com.easymall.entity.constants.Constants;
import com.easymall.entity.dto.ProductSaveDTO;
import com.easymall.entity.enums.ProductStatusEnum;
import com.easymall.entity.po.ProductPropertyValue;
import com.easymall.entity.po.ProductSku;
import com.easymall.entity.po.SysCategory;
import com.easymall.entity.query.*;
import com.easymall.entity.vo.ProductListVO;
import com.easymall.mappers.ProductPropertyValueMapper;
import com.easymall.mappers.ProductSkuMapper;
import com.easymall.service.SysCategoryService;
import com.easymall.utils.CopyTools;
import jakarta.annotation.Resource;

import org.springframework.stereotype.Service;

import com.easymall.entity.enums.PageSize;
import com.easymall.entity.po.ProductInfo;
import com.easymall.entity.vo.PaginationResultVO;
import com.easymall.mappers.ProductInfoMapper;
import com.easymall.service.ProductInfoService;
import com.easymall.utils.StringTools;
import org.springframework.transaction.annotation.Transactional;


/**
 * 商品信息 业务接口实现
 */
@Service("productInfoService")
public class ProductInfoServiceImpl implements ProductInfoService {

	@Resource
	private ProductInfoMapper<ProductInfo, ProductInfoQuery> productInfoMapper;
	@Resource
	private ProductPropertyValueMapper<ProductPropertyValue, ProductPropertyValueQuery> productPropertyValueMapper;
	@Resource
	private ProductSkuMapper<ProductSku, ProductSkuQuery> productSkuMapper;
	@Resource
	private SysCategoryService sysCategoryService;


	/**
	 * 根据条件查询列表
	 */
	@Override
	public List<ProductInfo> findListByParam(ProductInfoQuery param) {
		return this.productInfoMapper.selectList(param);
	}

	/**
	 * 根据条件查询列表
	 */
	@Override
	public Integer findCountByParam(ProductInfoQuery param) {
		return this.productInfoMapper.selectCount(param);
	}

	/**
	 * 分页查询方法
	 */
	@Override
	public PaginationResultVO<ProductInfo> findListByPage(ProductInfoQuery param) {
		int count = this.findCountByParam(param);
		int pageSize = param.getPageSize() == null ? PageSize.SIZE15.getSize() : param.getPageSize();

		SimplePage page = new SimplePage(param.getPageNo(), count, pageSize);
		param.setSimplePage(page);
		List<ProductInfo> list = this.findListByParam(param);
		PaginationResultVO<ProductInfo> result = new PaginationResultVO(count, page.getPageSize(), page.getPageNo(), page.getPageTotal(), list);
		return result;
	}

	/**
	 * 新增
	 */
	@Override
	public Integer add(ProductInfo bean) {
		return this.productInfoMapper.insert(bean);
	}

	/**
	 * 批量新增
	 */
	@Override
	public Integer addBatch(List<ProductInfo> listBean) {
		if (listBean == null || listBean.isEmpty()) {
			return 0;
		}
		return this.productInfoMapper.insertBatch(listBean);
	}

	/**
	 * 批量新增或者修改
	 */
	@Override
	public Integer addOrUpdateBatch(List<ProductInfo> listBean) {
		if (listBean == null || listBean.isEmpty()) {
			return 0;
		}
		return this.productInfoMapper.insertOrUpdateBatch(listBean);
	}

	/**
	 * 多条件更新
	 */
	@Override
	public Integer updateByParam(ProductInfo bean, ProductInfoQuery param) {
		StringTools.checkParam(param);
		return this.productInfoMapper.updateByParam(bean, param);
	}

	/**
	 * 多条件删除
	 */
	@Override
	public Integer deleteByParam(ProductInfoQuery param) {
		StringTools.checkParam(param);
		return this.productInfoMapper.deleteByParam(param);
	}

	/**
	 * 根据ProductId获取对象
	 */
	@Override
	public ProductInfo getProductInfoByProductId(String productId) {
		return this.productInfoMapper.selectByProductId(productId);
	}

	/**
	 * 根据ProductId修改
	 */
	@Override
	public Integer updateProductInfoByProductId(ProductInfo bean, String productId) {
		return this.productInfoMapper.updateByProductId(bean, productId);
	}

	/**
	 * 根据ProductId删除
	 */
	@Override
	public Integer deleteProductInfoByProductId(String productId) {
		return this.productInfoMapper.deleteByProductId(productId);
	}

	/**
	 * 新增/修改商品
	 * @param productSaveDTO
	 */
	@Override
	@Transactional(rollbackFor = Exception.class)
	public void saveProduct(ProductSaveDTO productSaveDTO) {
		//获取商品信息
		ProductInfo productInfo = productSaveDTO.getProductInfo();
		//获取商品属性
		List<ProductPropertyValue> productPropertyList = productSaveDTO.getProductPropertyList();
		//获取商品的sku信息
		List<ProductSku> skuList = productSaveDTO.getSkuList();
		//判断是新增还是修改
		boolean isAdd = StringTools.isEmpty(productInfo.getProductId());
		if (isAdd) {
			productInfo.setProductId(StringTools.getRandomString(Constants.LENGTH_15));
		}
		//设置商品属性的productId
		productPropertyList.forEach(p -> {
			p.setProductId(productInfo.getProductId());
		});
		//设置商品sku的productId
		skuList.forEach(s -> {
			s.setProductId(productInfo.getProductId());
		});

		productInfo.setStatus(null);
		productInfo.setCommendType(null);
		//获取商品的最大值和最小值
		Optional<ProductSku> minPrice = skuList.stream().min((s1, s2) -> s1.getPrice().compareTo(s2.getPrice()));
		Optional<ProductSku> maxPrice = skuList.stream().max((s1, s2) -> s1.getPrice().compareTo(s2.getPrice()));
		productInfo.setMinPrice(minPrice.get().getPrice());
		productInfo.setMaxPrice(maxPrice.get().getPrice());

		if(isAdd){
			productInfo.setCreateTime(new Date());
			productInfo.setStatus(ProductStatusEnum.OFF_SALE.getStatus());

			productInfoMapper.insert(productInfo);
			productPropertyValueMapper.insertBatch(productPropertyList);
			productSkuMapper.insertBatch(skuList);
		}
	}

	/**
	 * 分页查询更详细的商品信息
	 * @param param
	 * @return
	 */
	@Override
	public PaginationResultVO<ProductListVO> findListByPageListVO(ProductInfoQuery param) {
		//先查询ProductInfo中的基本信息
		PaginationResultVO<ProductInfo> paginationResultVO = findListByPage(param);
		//判断是否查到
		if(paginationResultVO.getPageTotal() == 0) {
			return new PaginationResultVO<>(new ArrayList<>());
		}
		List<ProductInfo> productInfoList = paginationResultVO.getList();

		//查询分类
		SysCategoryQuery sysCategoryQuery = new SysCategoryQuery();
		sysCategoryQuery.setConvert2Tree(false);
		List<SysCategory> categoryList = sysCategoryService.findListByParam(sysCategoryQuery);
		Map<String, SysCategory> categoryMap = categoryList.stream().collect(Collectors.toMap(SysCategory::getCategoryId, c -> c));

		//查询SKU
		List<String> productIdList = productInfoList.stream().map(ProductInfo::getProductId).collect(Collectors.toList());

		ProductSkuQuery skuQuery = new ProductSkuQuery();
		skuQuery.setProductIdList(productIdList);
		List<ProductSku> allSkuList = productSkuMapper.selectList(skuQuery);
		//一对多，一个商品的id对应多个sku
		Map<String, List<ProductSku>> skuMap = allSkuList.stream().collect(Collectors.groupingBy(ProductSku::getProductId));

		//把以上所查询到的所有信息进行处理
		List<ProductListVO> productListVOList = productInfoList.stream().map(item -> {
			ProductListVO productListVO = CopyTools.copy(item, ProductListVO.class);
			// 查询此商品的categoryId和pCategoryId对应的分类名称
			productListVO.setCategoryName(categoryMap.get(item.getpCategoryId()).getCategoryName() + "/" + categoryMap.get(item.getpCategoryId()).getCategoryName());
			// 利用productId查询此商品所有的sku
			List<ProductSku> skuList = skuMap.get(item.getProductId());
			// 获取sku数量
			productListVO.setSkuCount(skuList.size());
			// 获取总库存
			productListVO.setTotalStock(skuList.stream().mapToInt(ProductSku::getStock).sum());
			return productListVO;
		}).collect(Collectors.toList());

		return new PaginationResultVO<>(paginationResultVO.getTotalCount(), paginationResultVO.getPageSize(), paginationResultVO.getPageNo(), paginationResultVO.getPageTotal(), productListVOList);
	}
}