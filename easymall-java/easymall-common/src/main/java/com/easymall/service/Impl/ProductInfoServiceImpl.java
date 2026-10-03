package com.easymall.service.Impl;

import java.util.*;
import java.util.stream.Collectors;

import com.easymall.commonent.EsSearchComponent;
import com.easymall.entity.constants.Constants;
import com.easymall.entity.dto.ProductSaveDTO;
import com.easymall.entity.enums.ProductStatusEnum;
import com.easymall.entity.enums.ResponseCodeEnum;
import com.easymall.entity.po.ProductPropertyValue;
import com.easymall.entity.po.ProductSku;
import com.easymall.entity.po.SysCategory;
import com.easymall.entity.query.*;
import com.easymall.entity.vo.*;
import com.easymall.exception.BusinessException;
import com.easymall.mappers.ProductPropertyValueMapper;
import com.easymall.mappers.ProductSkuMapper;
import com.easymall.service.SysCategoryService;
import com.easymall.utils.CollectionComparator;
import com.easymall.utils.CopyTools;
import jakarta.annotation.Resource;

import org.springframework.stereotype.Service;

import com.easymall.entity.enums.PageSize;
import com.easymall.entity.po.ProductInfo;
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
	@Resource
	private EsSearchComponent esSearchComponent;


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
		List<ProductPropertyValue> productPropertyValueList = productSaveDTO.getProductPropertyList();
		//获取商品的sku信息
		List<ProductSku> skuList = productSaveDTO.getSkuList();
		//判断是新增还是修改
		boolean isAdd = StringTools.isEmpty(productInfo.getProductId());
		if (isAdd) {
			productInfo.setProductId(StringTools.getRandomNumber(Constants.LENGTH_15));
		}
		//设置商品属性的productId
		productPropertyValueList.forEach(p -> {
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

			//如果是新增，则新增商品信息、商品属性值、商品sku
			productInfoMapper.insert(productInfo);
			productPropertyValueMapper.insertBatch(productPropertyValueList);
			productSkuMapper.insertBatch(skuList);
		}else{
			ProductPropertyValueQuery productPropertyValueQuery = new ProductPropertyValueQuery();
			productPropertyValueQuery.setProductId(productInfo.getProductId());
			List<ProductPropertyValue> dbProductPropertyValueList = productPropertyValueMapper.selectList(productPropertyValueQuery);

			/*Map<String, ProductPropertyValue> dbProductPropertyValueMap = dbProductPropertyValueList.stream().
					collect(Collectors.toMap(ProductPropertyValue::getPropertyValueId, p -> p));

			List<ProductPropertyValue> productPropertyValueAddList = new ArrayList<>();
			List<ProductPropertyValue> productPropertyValueUpdateList = new ArrayList<>();
			List<ProductPropertyValue> productPropertyValueDeleteList = new ArrayList<>();

			//参数里有，数据库里没有的，为新增，两个都有为修改
			for (ProductPropertyValue item : productPropertyValueList) {
				if (dbProductPropertyValueMap.get(item.getPropertyValueId()) == null) {
					productPropertyValueAddList.add(item);
				}else {
					productPropertyValueUpdateList.add(item);
				}
			}
			//数据库里有，参数里没有的，为删除
			Map<String,ProductPropertyValue> propertyValueMap = productPropertyValueList.stream().
					collect(Collectors.toMap(ProductPropertyValue::getPropertyValueId, p -> p));

			for (ProductPropertyValue item : dbProductPropertyValueList) {
				if(propertyValueMap.get(item.getPropertyValueId()) == null){
					productPropertyValueDeleteList.add(item);
				}
			}

			//sku的新增，修改、删除
			List<ProductSku> productSkuAddList = new ArrayList<>();
			List<ProductSku> productSkuUpdateList = new ArrayList<>();
			List<ProductSku> productSkuDeleteList = new ArrayList<>();

			ProductSkuQuery productSkuQuery = new ProductSkuQuery();
			productSkuQuery.setProductId(productInfo.getProductId());
			List<ProductSku> dbProductSkuList = productSkuMapper.selectList(productSkuQuery);
			Map<String,ProductSku> dbSkuMap = dbProductSkuList.stream().
					collect(Collectors.toMap(ProductSku::getPropertyValueIdHash, p -> p));

			for (ProductSku item : skuList) {
				if(dbSkuMap.get(item.getPropertyValueIdHash()) == null){
					//新增
					productSkuAddList.add(item);
				}else {
					//修改
					productSkuUpdateList.add(item);
				}
			}

			//数据库里有，参数里没有的，为删除
			Map<String, ProductSku> skuMap = skuList.stream().
					collect(Collectors.toMap(ProductSku::getPropertyValueIdHash, p -> p));
			for (ProductSku item : dbProductSkuList) {
				if(skuMap.get(item.getPropertyValueIdHash()) == null){
					productSkuDeleteList.add(item);
				}
			}*/

			//修改不能修改的内容
			productInfo.setCategoryId(null);
			productInfo.setpCategoryId(null);
			productInfo.setStatus(null);

			// 利用新写的CollectionComparator工具类进行判断是否是新增/修改/删除
			CollectionComparator.DiffResult<ProductPropertyValue> propertyValueDiffResult =
					new CollectionComparator<ProductPropertyValue>().
							compare(productPropertyValueList,dbProductPropertyValueList,ProductPropertyValue::getPropertyValueId);

			productInfoMapper.updateByProductId(productInfo,productInfo.getProductId());

			//属性值操作
			//执行属性值的批量增加
			if(!propertyValueDiffResult.addList.isEmpty()){
				productPropertyValueMapper.insertBatch(propertyValueDiffResult.addList);
			}
			//执行属性值的批量修改
			if(!propertyValueDiffResult.updateList.isEmpty()){
				productPropertyValueMapper.updateBatch(productInfo.getProductId(),propertyValueDiffResult.updateList);
			}
			//执行属性值的批量删除
			if(!propertyValueDiffResult.deleteList.isEmpty()){
				productPropertyValueMapper.deleteBatch(productInfo.getProductId(),propertyValueDiffResult.deleteList);
			}

			ProductSkuQuery productSkuQuery = new ProductSkuQuery();
			productSkuQuery.setProductId(productInfo.getProductId());
			List<ProductSku> dbProductSkuList = productSkuMapper.selectList(productSkuQuery);

			// 利用新写的CollectionComparator工具类进行判断是否是新增/修改/删除
			CollectionComparator.DiffResult<ProductSku> productSkuDiffResult =
					new CollectionComparator<ProductSku>().
							compare(skuList,dbProductSkuList,ProductSku::getPropertyValueIdHash);

			//sku操作
			//sku的批量信息
			if(!productSkuDiffResult.addList.isEmpty()){
				productSkuMapper.insertBatch(productSkuDiffResult.addList);
			}
			//sku的批量修改
			if(!productSkuDiffResult.updateList.isEmpty()){
				productSkuMapper.updateBatch(productInfo.getProductId(),productSkuDiffResult.updateList);
			}
			//sku的批量删除
			if(!productSkuDiffResult.deleteList.isEmpty()){
				productSkuMapper.deleteBatch(productInfo.getProductId(),productSkuDiffResult.deleteList);
			}
		}
		//1. 将数据库信息写进es 2.将商品数据向量化
		saveProductInfoExtend(productInfo.getProductId());
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
			productListVO.setCategoryName(categoryMap.get(item.getpCategoryId()).getCategoryName() + "/" + categoryMap.get(item.getCategoryId()).getCategoryName());
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

	/**
	 * 获取到商品详细信息
	 * @param productId
	 * @return
	 */
	@Override
	public ProductInfoDetailVO getProductInfo(String productId) {
		// 根据productId查询到商品信息
		ProductInfo productInfo = productInfoMapper.selectByProductId(productId);
		if(productInfo == null) {
			throw new BusinessException(ResponseCodeEnum.CODE_600);
		}
		// 新增ProductPropertyValueQuery对象
		ProductPropertyValueQuery propertyValueQuery = new ProductPropertyValueQuery();
		propertyValueQuery.setProductId(productId);
		propertyValueQuery.setOrderBy("property_sort asc");
		//查询到具体的属性值
		/**
		 * | propertyId | propertyName | propertyValueId | propertyValue |
		 * 				|---|---|---|---|
		 * 				| color | 颜色 | red | 红色 |
		 * 				| color | 颜色 | black | 黑色 |
		 * 				| storage | 内存 | 128 | 128G |
		 * 				| storage | 内存 | 256 | 256G |
		 * 		这样的方式
		 */
		List<ProductPropertyValue> propertyValueList = productPropertyValueMapper.selectList(propertyValueQuery);

		//设置临时的属性集合
		List<ProductPropertyVO> productPropertyVOS = new ArrayList<>();
		//设置临时的属性对象的map
		Map<String,ProductPropertyVO> tempMap = new HashMap<>();
		for (ProductPropertyValue productPropertyValue : propertyValueList) {
			// 在Map集合当中根据属性Id获取到对应的属性对象ProductPropertyValue
			ProductPropertyVO productPropertyVO = tempMap.get(productPropertyValue.getPropertyId());
			// 新建此属性值的对象ProductPropertyValueVO
			ProductPropertyValueVO productPropertyValueVO = new ProductPropertyValueVO(productPropertyValue.getPropertyValueId(),
					productPropertyValue.getPropertyCover(),
					productPropertyValue.getPropertyValue(),
					productPropertyValue.getPropertyRemark()
			);

			if(productPropertyVO == null){
				//如果在map集合当中获取到的属性对象是null
				//则新建一个属性对象productPropertyVO，把基础的信息设置进去
				productPropertyVO = new ProductPropertyVO(productPropertyValue.getPropertyId(),
						productPropertyValue.getPropertyName(),
						productPropertyValue.getPropertySort(),
						productPropertyValue.getCoverType()
						);
				// 把此属性对象productPropertyVO放入map集合当中
				tempMap.put(productPropertyValue.getPropertyId(), productPropertyVO);
				// 创建productPropertyVO中的属性List<ProductPropertyValueVO>
				List<ProductPropertyValueVO> productPropertyValueVOS = new ArrayList<>();
				//把此属性的属性值对象给添加进集合当中
				productPropertyValueVOS.add(productPropertyValueVO);
				//把此集合给添加进属性对象productPropertyVO当中
				productPropertyVO.setPropertyValues(productPropertyValueVOS);
				//把属性对象添加进属性对象集合当中
				productPropertyVOS.add(productPropertyVO);
			}else {
				//如果根据遍历到的属性值所属的属性id在map集合当中获取到的属性对象不是null，说明属性已经存在
				//只需要在属性对象的属性值集合当中添加此属性值对象即可
				productPropertyVO.getPropertyValues().add(productPropertyValueVO);
			}
		}
		/**
		 * 最终成果类似于这样
		 * [
		 *   {
		 *     "propertyId": "color",
		 *     "propertyName": "颜色",
		 *     "propertyValues": [
		 *       {"propertyValueId": "red", "propertyValue": "红色"},
		 *       {"propertyValueId": "black", "propertyValue": "黑色"}
		 *     ]
		 *   },
		 *   {
		 *     "propertyId": "storage",
		 *     "propertyName": "内存",
		 *     "propertyValues": [
		 *       {"propertyValueId": "128", "propertyValue": "128G"},
		 *       {"propertyValueId": "256", "propertyValue": "256G"}
		 *     ]
		 *   }
		 * ]
		 */

		// 查询商品sku信息
		ProductSkuQuery skuQuery = new ProductSkuQuery();
		skuQuery.setProductId(productId);
		skuQuery.setOrderBy("sort asc");
		List<ProductSku> skuList = this.productSkuMapper.selectList(skuQuery);

		// 把商品的信息、商品属性和属性值、商品sku一起封装到商品详细信息VO中
		ProductInfoDetailVO productInfoDetailVO = new ProductInfoDetailVO();
		productInfoDetailVO.setProductInfo(productInfo);
		productInfoDetailVO.setProductPropertyList(productPropertyVOS);
		productInfoDetailVO.setSkuList(skuList);

		return productInfoDetailVO;
	}

	/**
	 * 修改商品的上架和下架状态
	 */
	@Override
	public void updateProductStatus(String productId, Integer status) {
		ProductStatusEnum productStatusEnum = ProductStatusEnum.getByStatus(status);
		if(productStatusEnum == null || productStatusEnum == ProductStatusEnum.DELETE){
			throw new BusinessException(ResponseCodeEnum.CODE_600);
		}

		ProductInfo dbInfo = this.productInfoMapper.selectByProductId(productId);
		if(ProductStatusEnum.DELETE.getStatus().equals(dbInfo.getStatus())) {
			throw new BusinessException(ResponseCodeEnum.CODE_600);
		}

		ProductInfo productInfo = new ProductInfo();
		productInfo.setStatus(status);
		this.updateProductInfoByProductId(productInfo,productId);

		//1.将数据库信息写入 es 2.将商品数据向量化
		saveProductInfoExtend(productId);

	}

	/**
	 * 逻辑删除商品
	 * @param productId 商品Id
	 */
	@Override
	public void deleteProduct(String productId) {
		ProductInfo productInfo = new ProductInfo();
		productInfo.setStatus(ProductStatusEnum.DELETE.getStatus());
		this.updateProductInfoByProductId(productInfo,productId);

		// 1.将数据库信息写入 es 2.将商品数据向量化
		saveProductInfoExtend(productId);
	}

	private void saveProductInfoExtend(String productId){
		esSearchComponent.saveProduct(productId);
		//TODO: 2.将商品数据向量化
	}
}