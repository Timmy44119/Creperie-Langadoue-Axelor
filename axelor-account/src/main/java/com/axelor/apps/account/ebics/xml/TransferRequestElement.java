/*
 * Copyright (c) 1990-2012 kopiLeft Development SARL, Bizerte, Tunisia
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License version 2.1 as published by the Free Software Foundation.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA 02111-1307 USA
 *
 * $Id$
 */

package com.axelor.apps.account.ebics.xml;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;

import com.axelor.apps.account.ebics.client.EbicsSession;
import com.axelor.apps.account.ebics.client.OrderType;
import com.axelor.apps.account.ebics.schema.h003.EbicsRequestDocument;
import com.axelor.apps.account.ebics.utils.Utils;
import com.axelor.exception.AxelorException;
import com.axelor.exception.db.IException;


/**
 * The <code>TransferRequestElement</code> is the common root element
 * for all ebics transfer for the bank server.
 *
 * @author Hachani
 *
 */
public abstract class TransferRequestElement extends DefaultEbicsRootElement {

  /**
   * Constructs a new <code>TransferRequestElement</code> element.
   * @param session the current ebics session
   * @param name the element name
   * @param type the order type
   * @param segmentNumber the segment number to be sent
   * @param lastSegment is it the last segment?
   * @param transactionID the transaction ID
   */
  public TransferRequestElement(EbicsSession session,
                                String name,
                                OrderType type,
                                int segmentNumber,
                                boolean lastSegment,
                                byte[] transactionId)
  {
    super(session);
    this.type = type;
    this.name = name;
    this.segmentNumber = segmentNumber;
    this.lastSegment = lastSegment;
    this.transactionId = transactionId;
  }

  @Override
  public void build() throws AxelorException {
    SignedInfo			signedInfo;

    buildTransfer();
    signedInfo = new SignedInfo(session.getUser(), getDigest());
    signedInfo.build();
    ((EbicsRequestDocument)document).getEbicsRequest().setAuthSignature(signedInfo.getSignatureType());
    ((EbicsRequestDocument)document).getEbicsRequest().getAuthSignature().setSignatureValue(EbicsXmlFactory.createSignatureValueType(signedInfo.sign(toByteArray())));
  }

  @Override
  public String getName() {
    return name + ".xml";
  }

  /**
   * Returns the digest value of the authenticated XML portions.
   * @return  the digest value.
   * @throws EbicsException Failed to retrieve the digest value.
   */
  public byte[] getDigest() throws AxelorException {
    addNamespaceDecl("ds", "http://www.w3.org/2000/09/xmldsig#");

    try {
      return MessageDigest.getInstance("SHA-256", "BC").digest(Utils.canonize(toByteArray()));
    } catch (NoSuchAlgorithmException e) {
      throw new AxelorException(e.getMessage(), IException.CONFIGURATION_ERROR);
    } catch (NoSuchProviderException e) {
      throw new AxelorException(e.getMessage(), IException.CONFIGURATION_ERROR);
    }
  }

  /**
   * Returns the order type of the element.
   * @return the order type element.
   */
  public String getOrderType() {
    return type.getOrderType();
  }

  @Override
  public byte[] toByteArray() {
    setSaveSuggestedPrefixes("http://www.ebics.org/H003", "");

    return super.toByteArray();
  }

  /**
   * Builds the transfer request.
   * @throws EbicsException
   */
  public abstract void buildTransfer() throws AxelorException;

  // --------------------------------------------------------------------
  // DATA MEMBERS
  // --------------------------------------------------------------------

  protected int				segmentNumber;
  protected boolean			lastSegment;
  protected byte[]			transactionId;
  private OrderType			type;
  private String 			name;
}
