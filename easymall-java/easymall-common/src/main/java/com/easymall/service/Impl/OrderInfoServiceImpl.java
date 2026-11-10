package com.easymall.service.Impl;

import java.math.BigDecimal;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.easymall.commonent.RedisComponent;
import com.easymall.commonent.SpringContext;
import com.easymall.entity.config.AppConfig;
import com.easymall.entity.constants.Constants;
import com.easymall.entity.dto.*;
import com.easymall.entity.enums.*;
import com.easymall.entity.po.*;
import com.easymall.entity.query.*;
import com.easymall.exception.BusinessException;
import com.easymall.mappers.*;
import com.easymall.service.PayChannel;
import com.easymall.validation.InputValidator;
import com.easymall.order.CheckoutInput;
import com.easymall.order.OrderRequest;
import com.easymall.order.OrderRequestService;
import com.easymall.task.BusinessTaskService;
import com.easymall.task.TaskSubmission;
import com.easymall.utils.JsonUtils;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import jakarta.annotation.Resource;

import org.apache.commons.lang3.ArrayUtils;
import org.springframework.stereotype.Service;

import com.easymall.entity.vo.PaginationResultVO;
import com.easymall.service.OrderInfoService;
import com.easymall.utils.StringTools;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;


/**
 * 订单信息 业务接口实现
 */
@Service("orderInfoService")
public class OrderInfoServiceImpl implements OrderInfoService {

	private static final Logger log = LoggerFactory.getLogger(OrderInfoServiceImpl.class);
	@Resource
	private OrderRequestService orderRequestService;
	@Resource
	private BusinessTaskService businessTaskService;
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
	@Resource
	private RedisComponent redisComponent;
	@Resource
	private AppConfig appConfig;
	@Resource
	private OrderLogisticsInfoMapper<OrderLogisticsInfo,OrderLogisticsInfoQuery> orderLogisticsInfoMapper;

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
	@Transactional(propagation = Propagation.NOT_SUPPORTED)
	public PayInfoDTO postOrder(String userId, PostOrderDTO postOrderDTO) {
		// 校验用户Id
		InputValidator.requireText(userId, 15);
		// 校验postOrderDTO 校验参数、合并相同商品/SKU并排序
		CheckoutInput input = CheckoutInput.normalize(postOrderDTO);
		// Redis中的发货地址只是可选快照；在数据库事务外读取，避免外部等待占用订单锁。
		LogisticsSendDTO sender = null;
		try {
			sender = redisComponent.getLogisticsInfo();
		} catch (RuntimeException unavailable) {
			log.warn("发件人快照不可用; code=SENDER_CACHE_UNAVAILABLE");
		}
		LogisticsSendDTO senderSnapshot = sender;
		//进行判断是否是重复提交或处理并发请求(主要还是校验)
		OrderRequest request = orderRequestService.place(userId, input,
				payOrderId -> createOrders(userId, input.order(), payOrderId, senderSnapshot));

		// 下单成功，获取支付信息
		// 由OrderRequest封装道支付信息里
		PayInfoDTO result = requestResult(request);
		if (!"CREATED".equals(request.getStatus())){
			return result;
		}
		//获取到支付订单号对应的主订单信息
		List<OrderInfo> orders = paymentGroup(userId, request.getPayOrderId());
		// 当前只保留旧Redis超时消费者执行订单关闭；ORDER_CREATED仅为可靠创建事件，11B再切换。
		for (OrderInfo order : orders) {
			if (!OrderStatusEnum.WAIT_PAYMENT.getStatus().equals(order.getOrderStatus())) {
				continue;
			}
			try {
				// 将订单加入延迟队列，等待后续定时任务检索
				redisComponent.addOrder2DelayQueueAt(Constants.REDIS_KEY_ORDER_DELAY_QUEUE, expiry(order), order.getOrderId());
			} catch (RuntimeException unavailable) {
				log.warn("旧版超时通知不可用; payOrderId={}, code=TIMEOUT_REDIS_UNAVAILABLE", request.getPayOrderId());
			}
		}
		// 返回支付信息
		return paymentForGroup(orders, result);
	}

	/** 【正式业务】仅由幂等服务的数据库事务回调调用；不请求支付渠道、不投递Redis任务。 */
	private BigDecimal createOrders(String userId, PostOrderDTO postOrderDTO, String payOrderId, LogisticsSendDTO sendDTO) {
		PayChannelEnum payChannelEnum = PayChannelEnum.getByPayScene(postOrderDTO.getPayMethod());
		if (payChannelEnum == null) {
			throw new BusinessException(ResponseCodeEnum.CODE_600);
		}
		OrderFromTypeEnum orderFromTypeEnum = OrderFromTypeEnum.getByType(postOrderDTO.getOrderFrom());
		if (orderFromTypeEnum == null) {
			throw new BusinessException(ResponseCodeEnum.CODE_600);
		}

		List<PostOrderItemDTO> itemDTOList = postOrderDTO.getOrderList();
		List<String> productIdList = itemDTOList.stream().map(PostOrderItemDTO::getProductId).distinct().toList();

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
		Map<List<String>, ProductPropertyValue> productPropertyValueMap = propertyValueList.stream().collect(Collectors.toMap(item -> List.of(item.getProductId(), item.getPropertyValueId()), Function.identity(),
				(data1,data2) -> data2));

		//查询商品Sku信息
		ProductSkuQuery skuQuery = new ProductSkuQuery();
		skuQuery.setProductIdList(productIdList);
		List<ProductSku> skuList = this.productSkuMapper.selectList(skuQuery);
		Map<List<String>, ProductSku> productSkuMap = skuList.stream().collect(Collectors.toMap(item -> List.of(item.getProductId(), item.getPropertyValueIds()), Function.identity(),
				(data1,data2) -> data2));

		Date curDate = new Date();

		//主订单
		List<OrderInfo> orderInfoList = new ArrayList<>();
		//订单详情
		List<OrderItem> orderItemList = new ArrayList<>();
		//购物车信息
		List<ProductCart> productCartList = new ArrayList<>();
		//订单物流信息
		//物流信息
		List<OrderLogisticsInfo> orderLogisticsInfoList = new ArrayList<>();

		Map<String,OrderInfo> orderInfoMap = new HashMap<>();

		// 支付业务号由order_request首次预留后固定复用，不能因重试重新生成。

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
				ProductPropertyValue propertyValue = productPropertyValueMap.get(List.of(itemDTO.getProductId(), propertyValueId));
				if (propertyValue == null) {
					throw new BusinessException("商品属性不存在");
				}
				propertyData.add(propertyValue.getPropertyName() + propertyValue.getPropertyValue());
				if (cover == null && !StringTools.isEmpty(propertyValue.getPropertyCover())) {
					cover = propertyValue.getPropertyCover();
				}
			}

			ProductSku productSku = productSkuMap.get(List.of(itemDTO.getProductId(), propertyValueIds));
			if (productSku == null || !StringTools.encodeByMD5(propertyValueIds).equals(productSku.getPropertyValueIdHash())) {
				throw new BusinessException("商品Sku不存在");
			}
			if (productSku.getPrice() == null || productSku.getPrice().signum() <= 0 || productSku.getPrice().scale() > 2) {
				throw new BusinessException("商品价格无效，请联系商家");
			}
			if(productSku.getStock() == null || productSku.getStock() < itemDTO.getBuyCount()){
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

				//记录地址信息
				OrderLogisticsInfo orderLogisticsInfo = new OrderLogisticsInfo();
				orderLogisticsInfo.setOrderId(orderInfo.getOrderId());
				orderLogisticsInfo.setUserId(userId);
				orderLogisticsInfo.setLogisticsStatus(LogisticsStatusEnum.PENDING_SHIPMENT.getStatus());
				orderLogisticsInfo.setReceiverName(userAddress.getAddressee());
				orderLogisticsInfo.setReceiverPhone(userAddress.getPhone());
				orderLogisticsInfo.setReceiverAddress(userAddress.getAddress());

				//设置默认的发货信息
				if (sendDTO != null){
					orderLogisticsInfo.setSenderName(sendDTO.getSenderName());
					orderLogisticsInfo.setSenderPhone(sendDTO.getSenderPhone());
					orderLogisticsInfo.setSenderAddress(sendDTO.getSenderAddress());
				}
				orderLogisticsInfoList.add(orderLogisticsInfo);

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

			if (orderInfo.getAmount().compareTo(new BigDecimal("99999999.99")) > 0) {
				throw new BusinessException("订单金额超过支持范围");
			}

			orderItemList.add(orderItem);

			//添加到订单详情
			orderInfo.getOrderItemList().add(orderItem);

			//删除购物车
			if (OrderFromTypeEnum.CART == orderFromTypeEnum) {
				//如果是从购物车订单,需要把订单中的商品添加到productCartList购物车集合当中，为了后续删除购物车中的这些东西
				ProductCart productCart = new ProductCart();
				productCart.setUserId(userId);
				productCart.setProductId(itemDTO.getProductId());
				productCart.setPropertyValueIdHash(StringTools.encodeByMD5(propertyValueIds));
				productCartList.add(productCart);
			}
		}

		this.orderInfoMapper.insertBatch(orderInfoList);

		this.orderItemMapper.insertBatch(orderItemList);

		//记录物流信息
		this.orderLogisticsInfoMapper.insertBatch(orderLogisticsInfoList);

		// 不改写已保存明细的正数量，单独构造扣减量；SQL还会再次聚合同一SKU。
		// -1 不表示只扣1件，而是把购买数量转换成负数。
		List<OrderItem> stockChanges = stockChanges(orderItemList, -1);
		Integer updateCount = this.productSkuMapper.updateStockBatch(stockChanges);
		if (!Objects.equals(updateCount, stockChanges.size())) {
			throw new BusinessException("库存不足");
		}

		//如果是购物车订单，删除购物车
		if (OrderFromTypeEnum.CART == orderFromTypeEnum) {
			this.productCartMapper.deleteBatch(productCartList);
		}

		// 与订单和库存共同提交的创建事件；当前不注册真实处理器，避免和旧Redis消费者双执行。
		businessTaskService.enqueue(new TaskSubmission("ORDER_CREATED", payOrderId, 0,
				JsonUtils.convertObj2Json(Map.of("payOrderId", payOrderId, "orderIds", orderInfoList.stream().map(OrderInfo::getOrderId).toList())), Duration.ZERO));

		return orderInfoList.stream().map(OrderInfo::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
	}

	/**
	 * 取消订单
	 * @param userId
	 * @param orderId
	 * @param orderStatusEnum
	 */
	@Override
	@Transactional(propagation = Propagation.NOT_SUPPORTED)
	public void cancelOrder(String userId, String orderId, OrderStatusEnum orderStatusEnum) {
		OrderInfo orderInfo = orderInfoMapper.selectByOrderId(orderId);
		if(orderInfo == null || (userId != null && !orderInfo.getUserId().equals(userId))){
			throw new BusinessException("订单不存在");
		}
		if (orderStatusEnum != OrderStatusEnum.CANCELLED && orderStatusEnum != OrderStatusEnum.CLOSED) {
			throw new BusinessException("取消状态无效");
		}
		Boolean changed = orderRequestService.inBusinessTransaction(() -> {
			// 同一支付组按固定顺序锁定；重复取消等待前一事务完成后读取最新状态。
			List<OrderInfo> group = orderInfoMapper.selectPaymentGroupForUpdate(orderInfo.getPayOrderId(), orderInfo.getUserId());
			if (group.isEmpty()) {
				throw new BusinessException("订单不存在");
			}
			if (group.stream().allMatch(order -> OrderStatusEnum.CANCELLED.getStatus().equals(order.getOrderStatus())
					|| OrderStatusEnum.CLOSED.getStatus().equals(order.getOrderStatus()))) {
				return false;
			}
			if (group.stream().anyMatch(order -> !OrderStatusEnum.WAIT_PAYMENT.getStatus().equals(order.getOrderStatus()))) {
				throw new BusinessException("订单状态已变化，无法取消");
			}

			List<String> ids = group.stream().map(OrderInfo::getOrderId).toList();
			Integer count = orderInfoMapper.updateOrderStatusBatch(orderStatusEnum.getStatus(), OrderStatusEnum.WAIT_PAYMENT.getStatus(), ids);
			if (!Objects.equals(count, group.size())) {
				throw new BusinessException("订单状态已变化，无法取消");
			}
			OrderItemQuery query = new OrderItemQuery();
			query.setOrderIdList(ids);
			List<OrderItem> changes = stockChanges(orderItemMapper.selectList(query), 1);
			// 扣减库存
			if (changes.isEmpty() || !Objects.equals(productSkuMapper.updateStockBatch(changes), changes.size())) {
				throw new BusinessException("库存归还未完成，取消操作已回滚");
			}
			// 提交工作任务
			businessTaskService.enqueue(new TaskSubmission("ORDER_CANCELLED", orderInfo.getPayOrderId(), 0,
					JsonUtils.convertObj2Json(Map.of("payOrderId", orderInfo.getPayOrderId())), Duration.ZERO));

			return true;
		});
		if (Boolean.TRUE.equals(changed)) {
			// 渠道关闭不再占用数据库锁；真实渠道竞态及失败补偿在步骤10/11B完善。
			try {
				cancelOrder4Channel(orderInfo);
			}
			catch (RuntimeException failure) {
				log.warn("通道关闭需要后续处理 payOrderId={}, code=CHANNEL_CLOSE_FAILED", orderInfo.getPayOrderId());
			}
		}
	}

	private void cancelOrder4Channel(OrderInfo orderInfo) {
		PayChannelEnum payChannelEnum = PayChannelEnum.getByPayScene(orderInfo.getPayScene());
		PayChannel payChannel = (PayChannel)SpringContext.getBean(payChannelEnum.getBeanName());
		payChannel.closeOrder(orderInfo.getPayOrderId());
	}

	/**
	 * 获取支付信息(进行在订单页面中点击付款)
	 * @param userId 用户Id
	 * @param orderId 主订单Id
	 * @return 支付信息
	 */
	@Override
	@Transactional(propagation = Propagation.NOT_SUPPORTED)
	public PayInfoDTO getPayInfo(String userId, String orderId) {
		OrderInfo orderInfo = orderInfoMapper.selectByOrderId(orderId);
		if (orderInfo == null || !orderInfo.getUserId().equals(userId)) {
			throw new BusinessException("订单不存在");
		}

		// 复用原支付号并计算整组金额，避免一个购物车支付组被拆成不同支付号或少付金额。
		PayInfoDTO result = new PayInfoDTO(null, orderInfo.getPayOrderId(), null);
		result.setRequestStatus("CREATED");
		return paymentForGroup(paymentGroup(userId, orderInfo.getPayOrderId()), result);
	}

	/** 查询本人的下单请求；只查询状态，不创建订单，也不请求支付渠道。 */
	@Override
	public PayInfoDTO getOrderRequest(String userId, String requestId) {
		InputValidator.requireText(userId, 15);
		if (requestId == null || !requestId.matches("[A-Za-z0-9_-]{16,64}")) throw new BusinessException("requestId无效");
		OrderRequest request = orderRequestService.find(userId, requestId);
		if (request == null) throw new BusinessException("下单请求不存在");
		return requestResult(request);
	}

	/**
	 * 封装支付信息
	 * @param request 下单请求
	 * @return 支付信息
	 */
	private PayInfoDTO requestResult(OrderRequest request) {
		PayInfoDTO result = new PayInfoDTO(null, request.getPayOrderId(), request.getAmount());
		result.setRequestId(request.getRequestId());
		result.setRequestStatus("PROCESSING".equals(request.getStatus()) && !request.isLeaseActive() ? "RETRY" : request.getStatus());
		return result;
	}

	/**
	 * 获取到支付订单号对应的主订单信息
	 * @param userId 用户Id
	 * @param payOrderId 支付订单号
	 * @return 主订单信息
	 */
	private List<OrderInfo> paymentGroup(String userId, String payOrderId) {
		OrderInfoQuery query = new OrderInfoQuery(); query.setUserId(userId); query.setPayOrderId(payOrderId);
		List<OrderInfo> orders = orderInfoMapper.selectList(query);
		if (orders.isEmpty()) throw new BusinessException("订单不存在");
		return orders;
	}

	/** 支付链接失败不回滚已提交的订单；客户端保留原业务号，可从原订单继续支付。 */
	private PayInfoDTO paymentForGroup(List<OrderInfo> orders, PayInfoDTO result) {
		result.setOrderId(orders.get(0).getOrderId());
		result.setAmount(orders.stream().map(OrderInfo::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add));
		if (orders.stream().anyMatch(order -> !OrderStatusEnum.WAIT_PAYMENT.getStatus().equals(order.getOrderStatus()) || expiry(order) <= System.currentTimeMillis())) {
			result.setPaymentStatus("NOT_PAYABLE"); return result;
		}
		PayChannelEnum channel = PayChannelEnum.getByPayScene(orders.get(0).getPayScene());
		try {
			PayChannel payChannel = (PayChannel) SpringContext.getBean(channel.getBeanName());
			PayInfoDTO link = payChannel.getPayUrl(channel, result.getPayOrderId(), "商城订单 " + result.getPayOrderId(), result.getAmount());
			if (link == null || StringTools.isEmpty(link.getPayInfo())) {
				throw new BusinessException("支付信息暂不可用");
			}
			result.setPayInfo(link.getPayInfo());
			result.setPaymentStatus("READY");
		} catch (RuntimeException unavailable) {
			log.warn("支付链接不可用; payOrderId={}, code=PAYMENT_LINK_UNAVAILABLE", result.getPayOrderId());
			result.setPaymentStatus("UNAVAILABLE");
		}
		return result;
	}

	private long expiry(OrderInfo order) {
		if (order.getOrderTime() == null) throw new BusinessException("订单创建时间无效");
		return order.getOrderTime().getTime() + appConfig.getOrderExpireMinute() * 60_000L;
	}

	/** 为相同SKU累加正/负变更量并排序，兼容历史重复明细；库存回写数量与实际SKU行数比较。 */
	private List<OrderItem> stockChanges(List<OrderItem> items, int sign) {
		Map<List<String>, OrderItem> merged = new HashMap<>();
		for (OrderItem item : items) {
			if (item.getBuyCount() == null || item.getBuyCount() <= 0) {
				throw new BusinessException("订单明细数量无效");
			}
			List<String> key = List.of(item.getProductId(), item.getPropertyValueIdHash());
			OrderItem change = merged.computeIfAbsent(key, ignored -> {
				OrderItem fresh = new OrderItem(); fresh.setProductId(item.getProductId());
				fresh.setPropertyValueIdHash(item.getPropertyValueIdHash()); fresh.setBuyCount(0); return fresh;
			});
			change.setBuyCount(Math.addExact(change.getBuyCount(), Math.multiplyExact(item.getBuyCount(), sign)));
		}

		return merged.values().stream().sorted(Comparator.comparing(OrderItem::getProductId).thenComparing(OrderItem::getPropertyValueIdHash)).toList();
	}

	/**
	 * 订单成功
	 * @param payOrderNotifyDTO
	 */
	@Override
	@Transactional(rollbackFor = Exception.class)
	public void payOrderSuccess(PayOrderNotifyDTO payOrderNotifyDTO) {
		if (payOrderNotifyDTO == null) throw new BusinessException("支付结果无效");
		InputValidator.requireText(payOrderNotifyDTO.getPayOrderId(), 64);
		InputValidator.requireText(payOrderNotifyDTO.getChannelOrderId(), 64);
		OrderInfoQuery groupQuery = new OrderInfoQuery();
		groupQuery.setPayOrderId(payOrderNotifyDTO.getPayOrderId());
		List<OrderInfo> group = orderInfoMapper.selectList(groupQuery);
		if (group.isEmpty()) throw new BusinessException("支付订单不存在");
		BigDecimal expected = group.stream().map(OrderInfo::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
		if (payOrderNotifyDTO.getAmount() == null || expected.compareTo(payOrderNotifyDTO.getAmount()) != 0) {
			throw new BusinessException("支付金额与订单金额不一致");
		}
		//更新订单状态
		OrderInfo orderInfo = new OrderInfo();
		orderInfo.setOrderStatus(OrderStatusEnum.PAID.getStatus());
		orderInfo.setChannelOrderId(payOrderNotifyDTO.getChannelOrderId());

		OrderInfoQuery orderInfoQuery = new OrderInfoQuery();
		orderInfoQuery.setPayOrderId(payOrderNotifyDTO.getPayOrderId());
		orderInfoQuery.setOrderStatus(OrderStatusEnum.WAIT_PAYMENT.getStatus());
		Integer changed = orderInfoMapper.updateByParam(orderInfo, orderInfoQuery);
		if (changed == null || changed == 0) return;

		//支付成功，自动发货,这里是为了发货，避免管理后台手动发货
		Runnable delivery = () -> {
			try { redisComponent.addOrder2DelayQueue(Constants.REDIS_KEY_ORDER_DELAY_QUEUE_DELIVERY,1,payOrderNotifyDTO.getPayOrderId()); }
			catch (RuntimeException unavailable) { log.warn("付款已确认，发货通知暂不可用; code=PAYMENT_DELIVERY_QUEUE_UNAVAILABLE"); }
		};
		if (org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()) {
			org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
					new org.springframework.transaction.support.TransactionSynchronization() {
						@Override public void afterCommit() { delivery.run(); }
					});
		} else delivery.run();

	}

	/**
	 * 确认收获
	 * @param userId
	 * @param orderId
	 */
	@Override
	@Transactional(rollbackFor = Exception.class)
	public void confirmOrder(String userId, String orderId) {
		OrderInfo updateInfo = new OrderInfo();
		updateInfo.setOrderStatus(OrderStatusEnum.COMPLETED.getStatus());

		OrderInfoQuery orderInfoQuery = new OrderInfoQuery();
		orderInfoQuery.setOrderId(orderId);
		orderInfoQuery.setUserId(userId);
		orderInfoQuery.setOrderStatusList(new Integer[]{OrderStatusEnum.SHIPPED.getStatus(),OrderStatusEnum.PARTIALLY_REFUNDED.getStatus()});
		Integer updateCount = this.orderInfoMapper.updateByParam(updateInfo, orderInfoQuery);
		if (updateCount == 0) {
			throw new BusinessException("该订单无法进行确认收货");
		}

		//设置销量
		OrderItemQuery orderItemQuery = new OrderItemQuery();
		orderItemQuery.setOrderId(orderId);
		orderItemQuery.setOrderItemStatus(OrderItemStatusEnum.NORMAL.getStatus());
		List<OrderItem> orderItemList = this.orderItemMapper.selectList(orderItemQuery);
		Integer buyCount = orderItemList.stream().mapToInt(OrderItem::getBuyCount).sum();
		this.productInfoMapper.updateProductTotalSale(orderItemList.get(0).getProductId(), buyCount);
	}

	/**
	 * 删除订单
	 * @param userId 用户Id
	 * @param orderId 订单Id
	 */
	@Override
	public void deleteOrder(String userId, String orderId) {
		OrderInfo orderInfo = orderInfoMapper.selectByOrderId(orderId);

		if(orderInfo == null || !orderInfo.getUserId().equals(userId)){
			throw new BusinessException("订单不存在");
		}

		Integer[] statusList = new Integer[]{OrderStatusEnum.CANCELLED.getStatus(),OrderStatusEnum.CLOSED.getStatus(),OrderStatusEnum.COMPLETED.getStatus()};
		if (!ArrayUtils.contains(statusList, orderInfo.getOrderStatus())){
			throw new BusinessException("订单无法删除");
		}
		OrderInfo updateOrderInfo = new OrderInfo();
		updateOrderInfo.setOrderStatus(OrderStatusEnum.DELETE.getStatus());

		OrderInfoQuery orderInfoQuery = new OrderInfoQuery();
		orderInfoQuery.setOrderId(orderId);
		orderInfoQuery.setUserId(userId);
		orderInfoQuery.setOrderStatusList(statusList);
		orderInfoMapper.updateByParam(updateOrderInfo, orderInfoQuery);
	}

	/**
	 * 退款
	 * @param orderItemId 订单子订单Id
	 */
	@Override
	@Transactional(rollbackFor = Exception.class)
	public void refundByOrderItemId(String userId,String orderItemId) {
		OrderItem orderItem = orderItemMapper.selectByOrderItemId(orderItemId);
		if(orderItem == null){
			throw new BusinessException(ResponseCodeEnum.CODE_600);
		}

		OrderInfo orderInfo = orderInfoMapper.selectByOrderId(orderItem.getOrderId());
		if(orderInfo == null || !orderInfo.getUserId().equals(userId)){
			throw new BusinessException("订单不存在");
		}

		Integer[] canRefundStatus = new Integer[]{OrderStatusEnum.PAID.getStatus(),OrderStatusEnum.SHIPPED.getStatus(),OrderStatusEnum.PARTIALLY_REFUNDED.getStatus()};

		if (!ArrayUtils.contains(canRefundStatus, orderInfo.getOrderStatus())){
			throw new BusinessException("订单无法退款");
		}

		if (!OrderItemStatusEnum.NORMAL.getStatus().equals(orderItem.getOrderItemStatus())) {
			throw new BusinessException("订单已经退款无法再次退款");
		}

		//生成退款订单
		String refundOrderId = StringTools.getRandomNumber(Constants.LENGTH_30);

		//更改订单子订单的状态
		OrderItem updateOrderItem = new OrderItem();
		updateOrderItem.setOrderItemStatus(OrderItemStatusEnum.REFUND.getStatus());
		updateOrderItem.setRefundOrderId(refundOrderId);

		OrderItemQuery orderItemQuery = new OrderItemQuery();
		orderItemQuery.setOrderItemId(orderItemId);
		orderItem.setOrderItemStatus(OrderItemStatusEnum.NORMAL.getStatus());
		Integer updateCount = orderItemMapper.updateByParam(updateOrderItem, orderItemQuery);

		if (updateCount == 0) {
			throw new BusinessException("退款失败，请稍后再试");
		}

		orderItemQuery = new OrderItemQuery();
		orderItemQuery.setOrderId(orderItem.getOrderId());
		orderItemQuery.setOrderItemStatus(OrderItemStatusEnum.NORMAL.getStatus());
		Integer leftCount = orderItemMapper.selectCount(orderItemQuery);

		//如果一个正常的子订单都没有，说明此订单已经全部退款
		OrderInfo updateInfo = new OrderInfo();
		updateInfo.setOrderStatus(leftCount == 0 ? OrderStatusEnum.REFUNDED.getStatus() : OrderStatusEnum.PARTIALLY_REFUNDED.getStatus());

		OrderInfoQuery orderInfoQuery = new OrderInfoQuery();
		orderInfoQuery.setOrderId(orderItem.getOrderId());
		orderInfoQuery.setOrderStatusList(canRefundStatus);

		Integer updateOrderCount = orderInfoMapper.updateByParam(updateInfo, orderInfoQuery);
		if (updateOrderCount == 0) {
			throw new BusinessException("退款失败,请稍后再试");
		}

		//支付宝退款
		PayChannelEnum payChannelEnum = PayChannelEnum.getByPayScene(orderInfo.getPayScene());
		PayChannel payChannel = (PayChannel) SpringContext.getBean(payChannelEnum.getBeanName());
		payChannel.refund(orderInfo.getPayOrderId(),refundOrderId,orderItem.getItemAmount());

	}

	/**
	 * 管理端首页获取数据(什么时间什么状态的订单总金额)
	 * @param orderTime 订单时间
	 * @param orderStatus 订单状态
	 * @return 订单总金额
	 */
	@Override
	public BigDecimal getOrderTotalAmount(String orderTime, Integer[] orderStatus) {
		return this.orderInfoMapper.selectOrderTotalAmount(orderTime,orderStatus);
	}
}
