package com.mydlp.ui.service;

import java.nio.ByteBuffer;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallbackWithoutResult;
import org.springframework.transaction.support.TransactionTemplate;

import com.mydlp.ui.dao.EndpointDAO;
import com.mydlp.ui.dao.EndpointStatusDAO;
import com.mydlp.ui.thrift.MyDLPUIThriftService;

@Service("endpointSyncService")
public class EndpointSyncServiceImpl implements EndpointSyncService {

	private static Logger logger = LoggerFactory.getLogger(EndpointSyncServiceImpl.class);

	@Autowired
	protected EndpointStatusDAO endpointStatusDAO;
	
	@Autowired
	protected EndpointDAO endpointDAO;

	@Autowired
	protected MyDLPUIThriftService thriftService;
	
	@Autowired
	@Qualifier("policyTransactionTemplate")
	protected TransactionTemplate transactionTemplate;
	
	@Async
	@Override
	public void asyncRegisterEndpointMeta(final String endpointId, final String ipAddress,
			final String usernameHash, final ByteBuffer payload) {
		final String endpointAlias = endpointDAO.getEndpointAlias(endpointId);
		if (endpointAlias == null) return;
		transactionTemplate.execute(new TransactionCallbackWithoutResult() {
			@Override
			protected void doInTransactionWithoutResult(TransactionStatus arg0) {
				asyncRegisterEndpointMetaFun(endpointId, ipAddress, usernameHash, payload);
			}
		});
	}
	
	public void asyncRegisterEndpointMetaFun(String endpointId, String ipAddress,
			String usernameHash, ByteBuffer payload) {
		try {
			Map<String,String> endpointMeta = thriftService.registerUserAddress(endpointId, ipAddress, usernameHash, payload);
			
			String endpointUsername = endpointMeta.get("user");
			String endpointVersion = endpointMeta.get("version");
			String osName = endpointMeta.get("os");
			String discoverInProgS = endpointMeta.get("discover_inprog");
			Boolean discoverInProg = Boolean.FALSE;
			if (discoverInProgS != null && discoverInProgS.equals("yes"))
			{
				discoverInProg = Boolean.TRUE;
			}
			endpointStatusDAO.upToDateEndpoint(endpointId, ipAddress, endpointUsername, osName, endpointVersion, discoverInProg);
		} catch (Throwable e) {
			logger.error("Runtime error occured when registering endpoint meta", e);
		}
		
	}
	
}
