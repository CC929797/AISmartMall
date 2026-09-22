package com.easymall.service.Impl;

import java.math.BigDecimal;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.easymall.entity.constants.Constants;
import com.easymall.entity.dto.PayInfoDTO;
import com.easymall.entity.dto.PostOrderDTO;
import com.easymall.entity.dto.PostOrderItemDTO;
import com.easymall.entity.enums.*;
import com.easymall.entity.po.*;
import com.easymall.entity.query.*;
import com.easymall.exception.BusinessException;
import com.easymall.mappers.*;
import jakarta.annotation.Resource;

import org.springframework.stereotype.Service;

import com.easymall.entity.vo.PaginationResultVO;
import com.easymall.service.OrderInfoService;
import com.easymall.utils.StringTools;
import org.springframework.transaction.annotation.Transactional;


/**
 * 订单信息 业务接口实现
 */
@Service("orderInfoService")
public class OrderInfoServiceImpl implements OrderInfoService {

	@Resource
	private OrderInfoMapper<OrderInfo, OrderInfoQuery> orderInfoMapper;
	@Resource
	private UserAddressMapper<UserAddress,String> userAddressMapper;
	@Resource
	private ProductInfoMapper<ProductInfo,ProductInfoQuery> productInfoMapper;
	@Resource
	private ProductPropertyValueMapper<ProductPropertyValue,ProductPropertyValueQuery> productPropertyValueMapper;
	@Resource
	private ProductSkuMapper<ProductSku,ProductSkuQuery> productSkuMapper;
	@Resource
	private OrderItemMapper<OrderItem,OrderItemQuery> orderItemMapper;
	@Resource
	private ProductCartMapper<ProductCart,ProductCartQuery> productCartMapper;

	/**
	 * 根据条件查询列表
	 */
	@Override
	public List<OrderInfo> findListByParam(OrderInfoQuery param) {
		return this.orderInfoMapper.selectList(param);
	}

	/**
	 * 根据条件查询列表
	 */
	@Override
	public Integer findCountByParam(OrderInfoQuery param) {
		return this.orderInfoMapper.selectCount(param);
	}

	/**
	 * 分页查询方法
	 */
	@Override
	public PaginationResultVO<OrderInfo> findListByPage(OrderInfoQuery param) {
		int count = this.findCountByParam(param);
		int pageSize = param.getPageSize() == null ? PageSize.SIZE15.getSize() : param.getPageSize();

		SimplePage page = new SimplePage(param.getPageNo(), count, pageSize);
		param.setSimplePage(page);
		List<OrderInfo> list = this.findListByParam(param);
		PaginationResultVO<OrderInfo> result = new PaginationResultVO(count, page.getPageSize(), page.getPageNo(), page.getPageTotal(), list);
		return result;
	}

	/**
	 * 新增
	 */
	@Override
	public Integer add(OrderInfo bean) {
		return this.orderInfoMapper.insert(bean);
	}

	/**
	 * 批量新增
	 */
	@Override
	public Integer addBatch(List<OrderInfo> listBean) {
		if (listBean == null || listBean.isEmpty()) {
			return 0;
		}
		return this.orderInfoMapper.insertBatch(listBean);
	}

	/**
	 * 批量新增或者修改
	 */
	@Override
	public Integer addOrUpdateBatch(List<OrderInfo> listBean) {
		if (listBean == null || listBean.isEmpty()) {
			return 0;
		}
		return this.orderInfoMapper.insertOrUpdateBatch(listBean);
	}

	/**
	 * 多条件更新
	 */
	@Override
	public Integer updateByParam(OrderInfo bean, OrderInfoQuery param) {
		StringTools.checkParam(param);
		return this.orderInfoMapper.updateByParam(bean, param);
	}

	/**
	 * 多条件删除
	 */
	@Override
	public Integer deleteByParam(OrderInfoQuery param) {
		StringTools.checkParam(param);
		return this.orderInfoMapper.deleteByParam(param);
	}

	/**
	 * 根据OrderId获取对象
	 */
	@Override
	public OrderInfo getOrderInfoByOrderId(String orderId) {
		return this.orderInfoMapper.selectByOrderId(orderId);
	}

	/**
	 * 根据OrderId修改
	 */
	@Override
	public Integer updateOrderInfoByOrderId(OrderInfo bean, String orderId) {
		return this.orderInfoMapper.updateByOrderId(bean, orderId);
	}

	/**
	 * 根据OrderId删除
	 */
	@Override
	public Integer deleteOrderInfoByOrderId(String orderId) {
		return this.orderInfoMapper.deleteByOrderId(orderId);
	}

	/**
	 * 订单提交
	 * @param userId 用户Id
	 * @param postOrderDTO 订单信息
	 */
	@Override
	@Transactional(rollbackFor = Exception.class)
	public PayInfoDTO postOrder(String userId, PostOrderDTO postOrderDTO) {
		PayChannelEnum payChannelEnum = PayChannelEnum.getByPayScene(postOrderDTO.getPayMethod());
		if (payChannelEnum == null) {
			throw new BusinessException(ResponseCodeEnum.CODE_600);
		}
		OrderFromTypeEnum orderFromTypeEnum = OrderFromTypeEnum.getByType(postOrderDTO.getOrderFrom());
		if (orderFromTypeEnum == null) {
			throw new BusinessException(ResponseCodeEnum.CODE_600);
		}

		List<PostOrderItemDTO> itemDTOList = postOrderDTO.getOrderList();
		List<String> productIdList = itemDTOList.stream().map(PostOrderItemDTO::getProductId).toList();

		//查询地址信息
		UserAddress userAddress = userAddressMapper.selectByAddressId(postOrderDTO.getAddressId());
		if (userAddress == null || !userAddress.getUserId().equals(userId)) {
			throw new BusinessException("地址信息不存在");
		}
		//查询商品信息
		ProductInfoQuery productInfoQuery = new ProductInfoQuery();
		productInfoQuery.setProductIdList(productIdList);
		List<ProductInfo> productInfoList = this.productInfoMapper.selectList(productInfoQuery);
		Map<String, ProductInfo> tempProductInfoMap = productInfoList.stream().collect(Collectors.toMap(ProductInfo::getProductId, Function.identity(),
				(data1, data2) -> data2));

		//查询商品属性信息
		ProductPropertyValueQuery propertyValueQuery = new ProductPropertyValueQuery();
		propertyValueQuery.setProductIdList(productIdList);
		List<ProductPropertyValue> propertyValueList = this.productPropertyValueMapper.selectList(propertyValueQuery);
		Map<String, ProductPropertyValue> productPropertyValueMap = propertyValueList.stream().collect(Collectors.toMap(item -> item.getProductId() + item.getPropertyValueId(), Function.identity(),
				(data1,data2) -> data2));

		//查询商品Sku信息
		ProductSkuQuery skuQuery = new ProductSkuQuery();
		skuQuery.setProductIdList(productIdList);
		List<ProductSku> skuList = this.productSkuMapper.selectList(skuQuery);
		Map<String, ProductSku> productSkuMap = skuList.stream().collect(Collectors.toMap(item -> item.getProductId() + item.getPropertyValueIds(), Function.identity(),
				(data1,data2) -> data2));

		Date curDate = new Date();

		//主订单
		List<OrderInfo> orderInfoList = new ArrayList<>();
		//订单详情
		List<OrderItem> orderItemList = new ArrayList<>();
		//购物车信息
		List<ProductCart> productCartList = new ArrayList<>();
		//订单物流信息
		//TODO: 物流信息

		Map<String,OrderInfo> orderInfoMap = new HashMap<>();


		String payOrderId = StringTools.createPayOrderId();

		for (PostOrderItemDTO itemDTO : itemDTOList) {
			ProductInfo productInfo = tempProductInfoMap.get(itemDTO.getProductId());
			if (productInfo == null || !ProductStatusEnum.ON_SALE.getStatus().equals(productInfo.getStatus())) {
				throw new BusinessException("商品不存在或者已下架");
			}
			String propertyValueIds = itemDTO.getPropertyValueIds();
			String[] propertyIdArray = propertyValueIds.split("-");
			List<String> propertyData = new ArrayList<>();
			String cover = null;
			for (String propertyValueId : propertyIdArray) {
				ProductPropertyValue propertyValue = productPropertyValueMap.get(itemDTO.getProductId() + propertyValueId);
				if (propertyValue == null) {
					throw new BusinessException("商品属性不存在");
				}
				propertyData.add(propertyValue.getPropertyName() + propertyValue.getPropertyValue());
				if (cover == null && !StringTools.isEmpty(propertyValue.getPropertyCover())) {
					cover = propertyValue.getPropertyCover();
				}
			}

			ProductSku productSku = productSkuMap.get(itemDTO.getProductId() + propertyValueIds);
			if (productSku == null) {
				throw new BusinessException("商品Sku不存在");
			}
			if(productSku.getStock() < itemDTO.getBuyCount()){
				throw new BusinessException("商品【" + productInfo.getProductName() + "】库存不足");
			}

			OrderInfo orderInfo = orderInfoMap.get(itemDTO.getProductId());
			if (orderInfo == null) {
				orderInfo = new OrderInfo();
				orderInfo.setOrderId(StringTools.createProductOrderId());
				orderInfo.setUserId(userId);
				orderInfo.setOrderTime(curDate);
				orderInfo.setOrderStatus(OrderStatusEnum.WAIT_PAYMENT.getStatus());
				orderInfo.setAmount(new BigDecimal("0.00"));
				orderInfo.setCommentStatus(OrderCommentStatusEnum.NOT_EVALUATED.getStatus());

				orderInfo.setPayChannel(payChannelEnum.getPayChannel());
				orderInfo.setPayScene(payChannelEnum.getPayScene());
				orderInfo.setPayOrderId(payOrderId);

				orderInfoMap.put(itemDTO.getProductId(), orderInfo);
				orderInfoList.add(orderInfo);


				List<OrderItem> orderItems = new ArrayList<>();
				orderInfo.setOrderItemList(orderItems);

				//TODO: 记录地址信息

				//TODO:设置默认的发货信息

			}

			cover = cover == null ? productInfo.getCover().split(",")[0] : cover;
			OrderItem orderItem = new OrderItem();
			orderItem.setOrderId(orderInfo.getOrderId());
			orderItem.setOrderItemId(orderInfo.getOrderId() + "_" + (orderInfo.getOrderItemList().size() + 1));
			orderItem.setCover(cover);
			orderItem.setProductId(itemDTO.getProductId());
			orderItem.setProductName(productInfo.getProductName());
			orderItem.setPropertyValueIdHash(StringTools.encodeByMD5(propertyValueIds));
			orderItem.setPropertyInfo(String.join(":", propertyData));
			orderItem.setItemAmount(productSku.getPrice().multiply(new BigDecimal(itemDTO.getBuyCount())));
			orderItem.setBuyCount(itemDTO.getBuyCount());
			orderItem.setOrderItemStatus(OrderItemStatusEnum.NORMAL.getStatus());
			orderItem.setRemark(itemDTO.getRemark());
			orderInfo.setAmount(orderInfo.getAmount().add(orderItem.getItemAmount()));

			orderItemList.add(orderItem);

			//添加到订单详情
			orderInfo.getOrderItemList().add(orderItem);

			//删除购物车
			if (OrderFromTypeEnum.CART == orderFromTypeEnum) {
				//如果是从购物车订单,需要把订单中的商品添加到productCartList购物车集合当中，为了后续删除购物车中的这些东西
				ProductCart productCart = new ProductCart();
				productCart.setUserId(userId);
				productCart.setProductId(itemDTO.getProductId());
				productCart.setPropertyValueIds(itemDTO.getPropertyValueIds());
				productCart.setPropertyValueIdHash(StringTools.encodeByMD5(propertyValueIds));
				productCartList.add(productCart);
			}
		}

		//扣减库存
		Integer updateCount = this.productSkuMapper.updateStockBatch(orderItemList);
		if (updateCount != orderItemList.size()) {
			throw new BusinessException("库存不足");
		}
		this.orderInfoMapper.insertBatch(orderInfoList);
		this.orderItemMapper.insertBatch(orderItemList);

		// TODO:记录物流信息

		//如果是购物车订单，删除购物车
		if (OrderFromTypeEnum.CART == orderFromTypeEnum) {
			this.productCartMapper.deleteBatch(productCartList);
		}

		//TODO: 获取支付信息
		return new PayInfoDTO();
	}
}