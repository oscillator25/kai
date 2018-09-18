package com.abc.bank.uancontract;

import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.codec.binary.Base64;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang.ArrayUtils;
import org.apache.commons.lang.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.abc.bank.VisualRecognitionService;
import com.abc.bank.bankcard.BankCardPojo;
import com.abc.bank.bankcard.BankCardService;
import com.abc.bank.customerinformation.CustomerInformationPojo;
import com.abc.bank.customerinformation.CustomerInformationService;
import com.abc.common.IService;
import com.abc.common.UanException;
import com.abc.common.bus.RequestBus;
import com.abc.common.bus.ThreadContext;
import com.abc.common.util.CfgValueHandler;
import com.abc.common.util.ConvertUtils;
import com.abc.common.util.JsonConvertor;
import com.abc.common.util.LogWriter;
import com.abc.common.util.UUIDNumberGenerator;
import com.abc.common.util.XMLConvertor;
import com.abc.uan.blockchain.AgreementPojo;
import com.abc.uan.blockchain.BlockChainService;
import com.abc.uan.blockchain.CardAccountPojo;
import com.abc.uan.blockchain.PersonPojo;
import com.ibm.watson.developer_cloud.service.security.IamOptions;
import com.ibm.watson.developer_cloud.visual_recognition.v3.VisualRecognition;
import com.ibm.watson.developer_cloud.visual_recognition.v3.model.DetectFacesOptions;
import com.ibm.watson.developer_cloud.visual_recognition.v3.model.DetectedFaces;

@Component
public class UanContractService implements IService {
	@Autowired
	private CustomerInformationService customerInformationService;
	@Autowired
	private XMLConvertor xmlConvertor;
	@Autowired
	private JsonConvertor jsonConvertor;
	@Autowired
	private UanContractBCDAO uanContractBCDAO;
	@Autowired
	private BlockChainService blockChainService;

	@Autowired
	private VisualRecognitionService visualRecognitionService;

	@Autowired
	private UUIDNumberGenerator uuidNumberGenerator;

	public String generate() {
		String idCard = (String) ((RequestBus) ThreadContext.getContext().getAttribute(RequestBus.REQUEST_BUS))
				.getAttributes().get(RequestBus.USER);
		CustomerInformationPojo ci = customerInformationService.confirmCustomer(idCard);
		// String str = "姓名：" + ci.getName() + "性别\n 身份证：" + ci.getIdCard() + "\n
		// 欢迎加入UAN";
		String str = ci.toString();
		return str;
	}

	public UanContractPojo contract(UanContractPojo uanContractPojo) {
		String idCard = (String) ((RequestBus) ThreadContext.getContext().getAttribute(RequestBus.REQUEST_BUS))
				.getAttributes().get(RequestBus.USER);
		CustomerInformationPojo customerInformationPojo = customerInformationService.confirmCustomer(idCard);
		uanContractPojo.setCustomerInformation(customerInformationPojo);
		for (PersonnelRelationshipPojo p : uanContractPojo.getPersonnelRelationships()) {
			p.setMasterId(idCard);
			p.setMasterName(customerInformationPojo.getName());
			if (StringUtils.isNotEmpty(p.getIdPhoto())) {
				Base64 base64 = new Base64();
				if (!visualRecognitionService.detectFace(base64.decode(p.getIdPhoto()))) {
					throw new UanException("亲友[" + p.getSlaveName() + "]提交证件照中未发现人脸。");
				}
			}
		}
		LogWriter.info(UanContractService.class, "签订合同：" + uanContractPojo);

		// UanContractBCPojo bc = new UanContractBCPojo();
		// bc.setMasterId(uanContractPojo.getCustomerInformation().getIdCard());
		// bc.setContent(xmlConvertor.toXml(uanContractPojo));
		// uanContractBCDAO.insert(bc);

		contract2BlockChain(uanContractPojo);

		return uanContractPojo;
	}

	private void contract2BlockChain(UanContractPojo uanContractPojo) {
		// 1.提交主合约
		Map<String, String> filter = new HashMap<String, String>();
		filter.put("party", uanContractPojo.getCustomerInformation().getIdCard());
		AgreementPojo tmpa = blockChainService.get("Agreement", uanContractPojo.getCustomerInformation().getIdCard(),
				filter, AgreementPojo.class);
		if (tmpa != null) {
			throw new UanException("客户[" + uanContractPojo.getCustomerInformation().getIdCard() + "]已签约，请终止或修订合约");
		}
		AgreementPojo agreementPojo = new AgreementPojo();
		String[] cs = new String[uanContractPojo.getBindingCards().size()];
		for (int i = 0; i < cs.length; i++) {
			cs[i] = uanContractPojo.getBindingCards().get(i).getCode();
		}
		agreementPojo.setBindingCardNums(cs);
		agreementPojo.setParty(uanContractPojo.getCustomerInformation().getIdCard());
		String[] ps = new String[uanContractPojo.getPersonnelRelationships().size()];
		for (int i = 0; i < ps.length; i++) {
			ps[i] = uanContractPojo.getPersonnelRelationships().get(i).getSlaveId();
		}
		agreementPojo.setPersonnelIdCards(ps);
		agreementPojo.setSerialNumber("abc-agreement-" + uuidNumberGenerator.generate());
		agreementPojo.setTrDetail(xmlConvertor.toXml(uanContractPojo));
		agreementPojo.setTrTime(ConvertUtils.timeToString(new Date()));
		agreementPojo.setTrType("create");

		blockChainService.post("Agreement", agreementPojo, AgreementPojo.class);

		// 2.提交人员信息
		postPerson(uanContractPojo.getCustomerInformation().getIdCard(),
				uanContractPojo.getCustomerInformation().getName(), null);
		for (PersonnelRelationshipPojo p : uanContractPojo.getPersonnelRelationships()) {
			postPerson(p.getSlaveId(), p.getSlaveName(), p.getIdPhoto());
		}

		// 3.提交卡信息
		for (BankCardPojo b : uanContractPojo.getBindingCards()) {
			postCardAccount(b);
		}

	}

	private void postCardAccount(BankCardPojo bankCardPojo) {
		Map<String, String> filter = new HashMap<String, String>();
		filter.put("cardNum", bankCardPojo.getCode());
		CardAccountPojo tmpp = blockChainService.get("CardAccount", bankCardPojo.getCode(), filter,
				CardAccountPojo.class);
		if (tmpp == null) {
			CardAccountPojo cardAccountPojo = new CardAccountPojo();
			cardAccountPojo.setIdCard(bankCardPojo.getIdCard());
			cardAccountPojo.setBankOfDeposit(bankCardPojo.getBankOfDeposit());
			cardAccountPojo.setCardNum(bankCardPojo.getCode());
			cardAccountPojo.setAmtLeft(bankCardPojo.getAmt().compareTo(new BigDecimal(1000)) >= 0 ? 50000D
					: ConvertUtils.stringToDoubleObject((ConvertUtils.bigDecimalToString(bankCardPojo.getAmt()))));
			cardAccountPojo.setState(bankCardPojo.getState());
			cardAccountPojo.setSerialNumber("abc-card-account-" + uuidNumberGenerator.generate());
			cardAccountPojo.setTrTime(ConvertUtils.timeToString(new Date()));
			cardAccountPojo.setTrType("create");
			cardAccountPojo.setTrDetail("No details");
			blockChainService.post("CardAccount", cardAccountPojo, CardAccountPojo.class);
		}
	}

	private void postPerson(String idCard, String name, String idPhoto) {
		Map<String, String> filter;
		filter = new HashMap<String, String>();
		filter.put("idCard", idCard);
		PersonPojo tmpp = blockChainService.get("Person", idCard, filter, PersonPojo.class);
		if (tmpp == null) {
			PersonPojo personPojo = new PersonPojo();
			personPojo.setIdCard(idCard);
			personPojo.setIdPhoto(StringUtils.isEmpty(idPhoto) ? "none" : idPhoto);
			personPojo.setName(name == null ? "none" : name);
			personPojo.setSerialNumber("abc-person-" + uuidNumberGenerator.generate());
			personPojo.setTrTime(ConvertUtils.timeToString(new Date()));
			personPojo.setTrType("create");
			personPojo.setTrDetail("No details");
			blockChainService.post("Person", personPojo, PersonPojo.class);
		}
	}

	public UanContractPojo loadMy2(String idCard) {
		UanContractBCPojo bc = uanContractBCDAO.load(idCard);
		UanContractPojo uanContractPojo = (UanContractPojo) xmlConvertor.toObject(bc.getContent());
		return uanContractPojo;
	}

	public AgreementPojo loadMy(String idCard) {
		// UanContractBCPojo bc = uanContractBCDAO.load(idCard);
		// UanContractPojo uanContractPojo = (UanContractPojo)
		// xmlConvertor.toObject(bc.getContent());

		Map<String, Object> filter = new HashMap<String, Object>();
		filter.put("party", idCard);
		AgreementPojo agreementPojo = blockChainService.get("Agreement", idCard, filter, AgreementPojo.class);

		return agreementPojo;
	}

	public List<PersonnelRelationshipPojo> findMasterPojos(String slaveId) {
		List<PersonnelRelationshipPojo> res = new ArrayList<PersonnelRelationshipPojo>();
		String[] ids = findMasters(slaveId);
		if (ArrayUtils.isEmpty(ids)) {
			return null;
		} else {
			for (String idCard : ids) {
				Map<String, Object> filter = new HashMap<String, Object>();
				filter.put("party", idCard);
				AgreementPojo agreementPojo = blockChainService.get("Agreement", idCard, filter, AgreementPojo.class);
				UanContractPojo uanContractPojo = (UanContractPojo) xmlConvertor.toObject(agreementPojo.getTrDetail());
				for (PersonnelRelationshipPojo p : uanContractPojo.getPersonnelRelationships()) {
					if (p.getSlaveId().equals(slaveId)) {
						res.add(p);
						break;
					}
				}

			}
			return res;
		}
	}

	public String[] findMasters(String slaveId) {
		Map<String, Object> filter = new HashMap<String, Object>();
		Map<String, String> where = new HashMap<String, String>();
		where.put("personnelIdCards", slaveId);
		filter.put("where", where);
		List<AgreementPojo> result = blockChainService.getList("Agreement", null, filter, AgreementPojo.class);
		String[] ss = new String[result.size()];
		for (int i = 0; i < ss.length; i++) {
			ss[i] = result.get(i).getParty();
		}
		return ss;
	}

	public List<BankCardPojo> findBindingCards(String masterId) {
		// UanContractBCPojo bc = uanContractBCDAO.load(masterId);
		// UanContractPojo uanContractPojo = (UanContractPojo)
		// xmlConvertor.toObject(bc.getContent());
		// return uanContractPojo.getBindingCards();

		List<BankCardPojo> bankCardPojos = new ArrayList<BankCardPojo>();
		Map<String, String> filter = new HashMap<String, String>();
		filter.put("party", masterId);
		AgreementPojo agreementPojo = blockChainService.get("Agreement", masterId, filter, AgreementPojo.class);
		filter = new HashMap<String, String>();
		filter.put("idCard", agreementPojo.getParty());
		PersonPojo personPojo = blockChainService.get("Person", agreementPojo.getParty(), filter, PersonPojo.class);
		for (String cn : agreementPojo.getBindingCardNums()) {
			filter = new HashMap<String, String>();
			filter.put("cardNum", cn);
			CardAccountPojo cardAccountPojo = blockChainService.get("CardAccount", cn, filter, CardAccountPojo.class);
			BankCardPojo bankCardPojo = new BankCardPojo();
			bankCardPojo
					.setAmt(ConvertUtils.stringToBigDecimal(ConvertUtils.doubleToString(cardAccountPojo.getAmtLeft())));
			bankCardPojo.setBankOfDeposit(cardAccountPojo.getBankOfDeposit());
			bankCardPojo.setCode(cardAccountPojo.getCardNum());
			bankCardPojo.setIdCard(cardAccountPojo.getIdCard());
			bankCardPojo.setState(cardAccountPojo.getState());
			bankCardPojo.setCustomerInformation(new CustomerInformationPojo());
			bankCardPojo.getCustomerInformation().setName(personPojo.getName());
			bankCardPojo.getCustomerInformation().setIdCard(personPojo.getIdCard());
			bankCardPojos.add(bankCardPojo);
		}

		if (CollectionUtils.isEmpty(bankCardPojos)) {
			return null;
		} else {
			return bankCardPojos;
		}

	}

	public BankCardPojo loadBankCard(String cardNum, String masterId) {
		// UanContractBCPojo bc = uanContractBCDAO.load(masterId);
		// UanContractPojo uanContractPojo = (UanContractPojo)
		// xmlConvertor.toObject(bc.getContent());
		//
		// List<BankCardPojo> bankCardPojos = uanContractPojo.getBindingCards();
		// for (BankCardPojo b : bankCardPojos) {
		// if (b.getCode().equals(cardNum)) {
		// return b;
		// }
		// }

		Map<String, Object> filter = new HashMap<String, Object>();
		filter.put("cardNum", cardNum);
		filter.put("idCard", masterId);
		CardAccountPojo cardAccountPojo = blockChainService.get("CardAccount", cardNum, filter, CardAccountPojo.class);
		if (cardAccountPojo == null) {
			return null;
		} else {
			BankCardPojo result = new BankCardPojo();
			result.setAmt(ConvertUtils.stringToBigDecimal(ConvertUtils.doubleToString(cardAccountPojo.getAmtLeft())));
			result.setBankOfDeposit(cardAccountPojo.getBankOfDeposit());
			result.setCode(cardAccountPojo.getCardNum());
			result.setIdCard(cardAccountPojo.getIdCard());
			result.setState(cardAccountPojo.getState());
			return result;
		}
	}

}