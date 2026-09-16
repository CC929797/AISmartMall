package com.easymall.service.Impl;

import java.util.List;

import com.easymall.entity.constants.Constants;
import com.easymall.entity.enums.DefaultTypeEnum;
import jakarta.annotation.Resource;

import org.springframework.stereotype.Service;

import com.easymall.entity.enums.PageSize;
import com.easymall.entity.query.UserAddressQuery;
import com.easymall.entity.po.UserAddress;
import com.easymall.entity.vo.PaginationResultVO;
import com.easymall.entity.query.SimplePage;
import com.easymall.mappers.UserAddressMapper;
import com.easymall.service.UserAddressService;
import com.easymall.utils.StringTools;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;


/**
 *  业务接口实现
 */
@Service("userAddressService")
public class UserAddressServiceImpl implements UserAddressService {

	@Resource
	private UserAddressMapper<UserAddress, UserAddressQuery> userAddressMapper;

	/**
	 * 根据条件查询列表
	 */
	@Override
	public List<UserAddress> findListByParam(UserAddressQuery param) {
		return this.userAddressMapper.selectList(param);
	}

	/**
	 * 根据条件查询列表
	 */
	@Override
	public Integer findCountByParam(UserAddressQuery param) {
		return this.userAddressMapper.selectCount(param);
	}

	/**
	 * 分页查询方法
	 */
	@Override
	public PaginationResultVO<UserAddress> findListByPage(UserAddressQuery param) {
		int count = this.findCountByParam(param);
		int pageSize = param.getPageSize() == null ? PageSize.SIZE15.getSize() : param.getPageSize();

		SimplePage page = new SimplePage(param.getPageNo(), count, pageSize);
		param.setSimplePage(page);
		List<UserAddress> list = this.findListByParam(param);
		PaginationResultVO<UserAddress> result = new PaginationResultVO(count, page.getPageSize(), page.getPageNo(), page.getPageTotal(), list);
		return result;
	}

	/**
	 * 新增
	 */
	@Override
	public Integer add(UserAddress bean) {
		return this.userAddressMapper.insert(bean);
	}

	/**
	 * 批量新增
	 */
	@Override
	public Integer addBatch(List<UserAddress> listBean) {
		if (listBean == null || listBean.isEmpty()) {
			return 0;
		}
		return this.userAddressMapper.insertBatch(listBean);
	}

	/**
	 * 批量新增或者修改
	 */
	@Override
	public Integer addOrUpdateBatch(List<UserAddress> listBean) {
		if (listBean == null || listBean.isEmpty()) {
			return 0;
		}
		return this.userAddressMapper.insertOrUpdateBatch(listBean);
	}

	/**
	 * 多条件更新
	 */
	@Override
	public Integer updateByParam(UserAddress bean, UserAddressQuery param) {
		StringTools.checkParam(param);
		return this.userAddressMapper.updateByParam(bean, param);
	}

	/**
	 * 多条件删除
	 */
	@Override
	public Integer deleteByParam(UserAddressQuery param) {
		StringTools.checkParam(param);
		return this.userAddressMapper.deleteByParam(param);
	}

	/**
	 * 根据AddressId获取对象
	 */
	@Override
	public UserAddress getUserAddressByAddressId(String addressId) {
		return this.userAddressMapper.selectByAddressId(addressId);
	}

	/**
	 * 根据AddressId修改
	 */
	@Override
	public Integer updateUserAddressByAddressId(UserAddress bean, String addressId) {
		return this.userAddressMapper.updateByAddressId(bean, addressId);
	}

	/**
	 * 根据AddressId删除
	 */
	@Override
	public Integer deleteUserAddressByAddressId(String addressId) {
		return this.userAddressMapper.deleteByAddressId(addressId);
	}

	/**
	 * 修改地址为默认地址
	 * @param addressId 修改的地址Id
	 * @param userId 修改的用户Id
	 */
	@Override
	@Transactional(rollbackFor = Exception.class)
	public void updateDefaultAddress(String addressId, String userId) {
		restDefault(userId);

		UserAddress userAddress = new UserAddress();
		userAddress.setDefaultType(DefaultTypeEnum.DEFAULT.getType());

		UserAddressQuery userAddressQuery = new UserAddressQuery();
		userAddressQuery.setUserId(userId);
		userAddressQuery.setAddressId(addressId);

		this.userAddressMapper.updateByParam(userAddress, userAddressQuery);
	}

	/**
	 * 新增或修改地址信息(与上面add/update不同的是上面的在新增或修改时选择默认时，会出现地址薄有两个默认地址)
	 * @param userAddress 新增/修改的地址
	 */
	@Override
	@Transactional(rollbackFor = Exception.class)
	public void saveAddress(UserAddress userAddress) {
		//如果新增或修改的时候选择了默认地址，则要把数据库里的默认地址先给设置为未默认状态
		if (DefaultTypeEnum.DEFAULT.getType().equals(userAddress.getDefaultType())) {
			//把数据库里的默认地址变为未默认状态
			restDefault(userAddress.getUserId());
		}
		// 判断是新增或修改地址(addressId为null则是新增，反之则是修改)
		if (StringTools.isEmpty(userAddress.getAddressId())) {
			userAddress.setAddressId(StringTools.getRandomNumber(Constants.LENGTH_15));
			this.userAddressMapper.insert(userAddress);
		}else {
			UserAddressQuery userAddressQuery = new UserAddressQuery();
			userAddressQuery.setAddressId(userAddress.getAddressId());
			userAddressQuery.setUserId(userAddress.getUserId());

			this.userAddressMapper.updateByParam(userAddress, userAddressQuery);
		}
	}

	/**
	 * 把数据库里的默认地址变为未默认状态
	 */
	private void restDefault(String userId) {
		UserAddress updateAddress = new UserAddress();
		updateAddress.setDefaultType(DefaultTypeEnum.NOT_DEFAULT.getType());

		UserAddressQuery query = new UserAddressQuery();
		query.setUserId(userId);
		this.userAddressMapper.updateByParam(updateAddress, query);
	}

}