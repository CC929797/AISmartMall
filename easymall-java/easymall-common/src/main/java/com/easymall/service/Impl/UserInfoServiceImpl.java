package com.easymall.service.Impl;

import java.util.Date;
import java.util.List;

import com.easymall.commonent.RedisComponent;
import com.easymall.entity.constants.Constants;
import com.easymall.entity.dto.TokenUserInfoDTO;
import com.easymall.entity.enums.UserSexEnum;
import com.easymall.entity.enums.UserStatusEnum;
import com.easymall.entity.vo.ResponseVO;
import com.easymall.exception.BusinessException;
import com.easymall.utils.CopyTools;
import com.easymall.security.PasswordService;
import com.easymall.security.CredentialLock;
import jakarta.annotation.Resource;

import org.springframework.stereotype.Service;

import com.easymall.entity.enums.PageSize;
import com.easymall.entity.query.UserInfoQuery;
import com.easymall.entity.po.UserInfo;
import com.easymall.entity.vo.PaginationResultVO;
import com.easymall.entity.query.SimplePage;
import com.easymall.mappers.UserInfoMapper;
import com.easymall.service.UserInfoService;
import com.easymall.utils.StringTools;
import org.springframework.web.bind.annotation.RequestMapping;


/**
 * 用户信息 业务接口实现
 */
@Service("userInfoService")
public class UserInfoServiceImpl implements UserInfoService {

	@Resource
	private UserInfoMapper<UserInfo, UserInfoQuery> userInfoMapper;
	@Resource
	private RedisComponent redisComponent;
	@Resource
	private PasswordService passwordService;
	@Resource
	private CredentialLock credentialLock;

	/**
	 * 根据条件查询列表
	 */
	@Override
	public List<UserInfo> findListByParam(UserInfoQuery param) {
		return this.userInfoMapper.selectList(param);
	}

	/**
	 * 根据条件查询列表
	 */
	@Override
	public Integer findCountByParam(UserInfoQuery param) {
		return this.userInfoMapper.selectCount(param);
	}

	/**
	 * 分页查询方法
	 */
	@Override
	public PaginationResultVO<UserInfo> findListByPage(UserInfoQuery param) {
		int count = this.findCountByParam(param);
		int pageSize = param.getPageSize() == null ? PageSize.SIZE15.getSize() : param.getPageSize();

		SimplePage page = new SimplePage(param.getPageNo(), count, pageSize);
		param.setSimplePage(page);
		List<UserInfo> list = this.findListByParam(param);
		PaginationResultVO<UserInfo> result = new PaginationResultVO(count, page.getPageSize(), page.getPageNo(), page.getPageTotal(), list);
		return result;
	}

	/**
	 * 新增
	 */
	@Override
	public Integer add(UserInfo bean) {
		return this.userInfoMapper.insert(bean);
	}

	/**
	 * 批量新增
	 */
	@Override
	public Integer addBatch(List<UserInfo> listBean) {
		if (listBean == null || listBean.isEmpty()) {
			return 0;
		}
		return this.userInfoMapper.insertBatch(listBean);
	}

	/**
	 * 批量新增或者修改
	 */
	@Override
	public Integer addOrUpdateBatch(List<UserInfo> listBean) {
		if (listBean == null || listBean.isEmpty()) {
			return 0;
		}
		return this.userInfoMapper.insertOrUpdateBatch(listBean);
	}

	/**
	 * 多条件更新
	 */
	@Override
	public Integer updateByParam(UserInfo bean, UserInfoQuery param) {
		StringTools.checkParam(param);
		return this.userInfoMapper.updateByParam(bean, param);
	}

	/**
	 * 多条件删除
	 */
	@Override
	public Integer deleteByParam(UserInfoQuery param) {
		StringTools.checkParam(param);
		return this.userInfoMapper.deleteByParam(param);
	}

	/**
	 * 根据UserId获取对象
	 */
	@Override
	public UserInfo getUserInfoByUserId(String userId) {
		return this.userInfoMapper.selectByUserId(userId);
	}

	/**
	 * 根据UserId修改
	 */
	@Override
	public Integer updateUserInfoByUserId(UserInfo bean, String userId) {
		return this.userInfoMapper.updateByUserId(bean, userId);
	}

	/**
	 * 根据UserId删除
	 */
	@Override
	public Integer deleteUserInfoByUserId(String userId) {
		return this.userInfoMapper.deleteByUserId(userId);
	}

	/**
	 * 根据Email获取对象
	 */
	@Override
	public UserInfo getUserInfoByEmail(String email) {
		return this.userInfoMapper.selectByEmail(email);
	}

	/**
	 * 根据Email修改
	 */
	@Override
	public Integer updateUserInfoByEmail(UserInfo bean, String email) {
		return this.userInfoMapper.updateByEmail(bean, email);
	}

	/**
	 * 根据Email删除
	 */
	@Override
	public Integer deleteUserInfoByEmail(String email) {
		return this.userInfoMapper.deleteByEmail(email);
	}

	/**
	 * 根据NickName获取对象
	 */
	@Override
	public UserInfo getUserInfoByNickName(String nickName) {
		return this.userInfoMapper.selectByNickName(nickName);
	}

	/**
	 * 根据NickName修改
	 */
	@Override
	public Integer updateUserInfoByNickName(UserInfo bean, String nickName) {
		return this.userInfoMapper.updateByNickName(bean, nickName);
	}

	/**
	 * 根据NickName删除
	 */
	@Override
	public Integer deleteUserInfoByNickName(String nickName) {
		return this.userInfoMapper.deleteByNickName(nickName);
	}

	@Override
	public void register(String email, String nickName, String registerPassword) {
		//校验注册的密码格式
		passwordService.validateNewPassword(registerPassword);
		//查询邮箱是否被注册
		UserInfo userInfo = this.userInfoMapper.selectByEmail(email);
		if (userInfo != null) {
			throw new BusinessException("邮箱账号已经存在");
		}
		//查询昵称是否重复
		UserInfo nickNameUserInfo = this.userInfoMapper.selectByNickName(nickName);
		if (nickNameUserInfo != null) {
			throw new BusinessException("昵称不能重复");
		}
		// 封装UserInfo对象，然后插入到数据库当中
		String userId = StringTools.getRandomNumber(Constants.LENGTH_10);
		userInfo = new UserInfo();
		userInfo.setUserId(userId);
		userInfo.setEmail(email);
		userInfo.setNickName(nickName);
		userInfo.setPassword(passwordService.encode(registerPassword));
		userInfo.setJoinTime(new Date());
		userInfo.setStatus(UserStatusEnum.ENABLE.getStatus());
		userInfo.setSex(UserSexEnum.SECRECY.getType());
		this.userInfoMapper.insert(userInfo);
	}

	@Override
	public TokenUserInfoDTO login(String email, String password,String ip) {
		UserInfo userInfo = this.userInfoMapper.selectByEmail(email);
		if (userInfo == null || !passwordService.matches(password, userInfo.getPassword())) {
			throw new BusinessException("账号或者密码错误");
		}

		if (UserStatusEnum.DISABLE.getStatus().equals(userInfo.getStatus())) {
			throw new BusinessException("账号已被封禁");
		}

		// 高成本哈希在锁外进行，锁内重新确认密码和状态，禁止旧密码签发新会话。
		String upgraded = passwordService.needsUpgrade(userInfo.getPassword()) ? passwordService.encode(password) : null;
		//使用CredentialLock进行密码升级和会话签发的互斥,分布式锁的方式，确保在登录的过程中不会出现密码升级和会话签发的并发情况
		return credentialLock.withUser(userInfo.getUserId(), () -> {
			UserInfo current = userInfoMapper.selectByUserId(userInfo.getUserId());
			if (current == null || !userInfo.getPassword().equals(current.getPassword())
					|| !UserStatusEnum.ENABLE.getStatus().equals(current.getStatus())) {
				throw new BusinessException("账号状态或密码已变化，请重新登录");
			}
			if (upgraded != null && userInfoMapper.replacePasswordIfCurrent(current.getUserId(), current.getPassword(), upgraded) != 1) {
				throw new BusinessException("密码已变化，请重新登录");
			}
			UserInfo updateInfo = new UserInfo();
			updateInfo.setLastLoginTime(new Date());
			updateInfo.setLastLoginIp(ip);
			userInfoMapper.updateByUserId(updateInfo, current.getUserId());
			TokenUserInfoDTO tokenUserInfoDTO = CopyTools.copy(current, TokenUserInfoDTO.class);
			redisComponent.saveTokenInfo(tokenUserInfoDTO);
			return tokenUserInfoDTO;
		});
	}

	/**
	 * 修改密码
	 * @param userId 用户ID
	 * @param oldPassword 旧密码
	 * @param password 新密码
	 */
	@Override
	public void updatePassword(String userId, String oldPassword, String password) {
		passwordService.validateNewPassword(password);
		UserInfo userInfo = this.userInfoMapper.selectByUserId(userId);
		if (userInfo == null) {
			throw new BusinessException("用户不存在");
		}
		if (!passwordService.matches(oldPassword, userInfo.getPassword())) {
			throw new BusinessException("原始密码错误");
		}
		String encoded = passwordService.encode(password);
		//使用CredentialLock进行密码升级和会话签发的互斥,分布式锁的方式，确保在修改密码的过程中不会出现登录和会话签发的并发情况
		credentialLock.withUser(userId, () -> {
			UserInfo current = userInfoMapper.selectByUserId(userId);
			if (current == null || !userInfo.getPassword().equals(current.getPassword())
					|| !UserStatusEnum.ENABLE.getStatus().equals(current.getStatus())) {
				throw new BusinessException("账号状态或密码已变化，请重新登录");
			}
			// 先撤销会话再写密码：撤销失败时不改密；数据库失败时旧会话也不会残留。
			redisComponent.forceLogout(userId);
			if (userInfoMapper.replacePasswordIfCurrent(userId, current.getPassword(), encoded) != 1) {
				throw new BusinessException("密码已变化，请重新登录");
			}
			return null;
		});
	}


}