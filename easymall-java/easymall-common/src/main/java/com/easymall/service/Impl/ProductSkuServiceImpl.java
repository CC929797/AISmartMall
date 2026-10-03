package com.easymall.service.Impl;

import java.util.List;

import com.easymall.exception.BusinessException;
import jakarta.annotation.Resource;

import org.springframework.stereotype.Service;

import com.easymall.entity.enums.PageSize;
import com.easymall.entity.query.ProductSkuQuery;
import com.easymall.entity.po.ProductSku;
import com.easymall.entity.vo.PaginationResultVO;
import com.easymall.entity.query.SimplePage;
import com.easymall.mappers.ProductSkuMapper;
import com.easymall.service.ProductSkuService;
import com.easymall.utils.StringTools;


/**
 *  业务接口实现
 */
@Service("productSkuService")
public class ProductSkuServiceImpl implements ProductSkuService {

	@Resource
	private ProductSkuMapper<ProductSku, ProductSkuQuery> productSkuMapper;

	/**
	 * 根据条件查询列表
	 */
	@Override
	public List<ProductSku> findListByParam(ProductSkuQuery param) {
		return this.productSkuMapper.selectList(param);
	}

	/**
	 * 根据条件查询列表
	 */
	@Override
	public Integer findCountByParam(ProductSkuQuery param) {
		return this.productSkuMapper.selectCount(param);
	}

	/**
	 * 分页查询方法
	 */
	@Override
	public PaginationResultVO<ProductSku> findListByPage(ProductSkuQuery param) {
		int count = this.findCountByParam(param);
		int pageSize = param.getPageSize() == null ? PageSize.SIZE15.getSize() : param.getPageSize();

		SimplePage page = new SimplePage(param.getPageNo(), count, pageSize);
		param.setSimplePage(page);
		List<ProductSku> list = this.findListByParam(param);
		PaginationResultVO<ProductSku> result = new PaginationResultVO(count, page.getPageSize(), page.getPageNo(), page.getPageTotal(), list);
		return result;
	}

	/**
	 * 新增
	 */
	@Override
	public Integer add(ProductSku bean) {
		return this.productSkuMapper.insert(bean);
	}

	/**
	 * 批量新增
	 */
	@Override
	public Integer addBatch(List<ProductSku> listBean) {
		if (listBean == null || listBean.isEmpty()) {
			return 0;
		}
		return this.productSkuMapper.insertBatch(listBean);
	}

	/**
	 * 批量新增或者修改
	 */
	@Override
	public Integer addOrUpdateBatch(List<ProductSku> listBean) {
		if (listBean == null || listBean.isEmpty()) {
			return 0;
		}
		return this.productSkuMapper.insertOrUpdateBatch(listBean);
	}

	/**
	 * 多条件更新
	 */
	@Override
	public Integer updateByParam(ProductSku bean, ProductSkuQuery param) {
		StringTools.checkParam(param);
		return this.productSkuMapper.updateByParam(bean, param);
	}

	/**
	 * 多条件删除
	 */
	@Override
	public Integer deleteByParam(ProductSkuQuery param) {
		StringTools.checkParam(param);
		return this.productSkuMapper.deleteByParam(param);
	}

	/**
	 * 根据ProductIdAndPropertyValueIdHash获取对象
	 */
	@Override
	public ProductSku getProductSkuByProductIdAndPropertyValueIdHash(String productId, String propertyValueIdHash) {
		return this.productSkuMapper.selectByProductIdAndPropertyValueIdHash(productId, propertyValueIdHash);
	}

	/**
	 * 根据ProductIdAndPropertyValueIdHash修改
	 */
	@Override
	public Integer updateProductSkuByProductIdAndPropertyValueIdHash(ProductSku bean, String productId, String propertyValueIdHash) {
		return this.productSkuMapper.updateByProductIdAndPropertyValueIdHash(bean, productId, propertyValueIdHash);
	}

	/**
	 * 根据ProductIdAndPropertyValueIdHash删除
	 */
	@Override
	public Integer deleteProductSkuByProductIdAndPropertyValueIdHash(String productId, String propertyValueIdHash) {
		return this.productSkuMapper.deleteByProductIdAndPropertyValueIdHash(productId, propertyValueIdHash);
	}

	/**
	 * 管理员更新库存
	 * @param productId 商品Id
	 * @param propertyValueIdHash hash值
	 * @param stock 库存
	 */
	@Override
	public void updateStock(String productId, String propertyValueIdHash, Integer stock) {
		Integer changeCount = this.productSkuMapper.updateStock(productId, propertyValueIdHash, stock);
		if (changeCount == 0) {
			throw new BusinessException("库存不足");
		}
	}
}