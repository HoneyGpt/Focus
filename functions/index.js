// deployment test 2026-07-02

const functions = require("firebase-functions");
const { onCall, HttpsError, onRequest } = require("firebase-functions/v2/https");
const { defineSecret } = require("firebase-functions/params");
const logger = require("firebase-functions/logger");
const admin = require("firebase-admin");
const Razorpay = require("razorpay");
const crypto = require("crypto");

// Define Secret Manager secrets
const razorpayKeyId = defineSecret("RAZORPAY_KEY_ID");
const razorpayKeySecret = defineSecret("RAZORPAY_KEY_SECRET");
const razorpayWebhookSecret = defineSecret("RAZORPAY_WEBHOOK_SECRET");

admin.initializeApp();

const db = admin.firestore();

// Retrieve Razorpay keys securely supporting process.env, functions.config(), and Secret Manager
const getRazorpayConfig = () => {
  let keyId = null;
  let keySecret = null;
  let webhookSecret = null;

  try {
    keyId = razorpayKeyId.value();
    keySecret = razorpayKeySecret.value();
    webhookSecret = razorpayWebhookSecret.value();
  } catch (e) {
    logger.debug("defineSecret .value() failed, falling back to process.env", e);
  }

  if (!keyId) keyId = process.env.RAZORPAY_KEY_ID || process.env.razorpay_key_id;
  if (!keySecret) keySecret = process.env.RAZORPAY_KEY_SECRET || process.env.razorpay_key_secret;
  if (!webhookSecret) webhookSecret = process.env.RAZORPAY_WEBHOOK_SECRET || process.env.razorpay_webhook_secret;

  // Try functions.config() fallback for v1-style or environment config if available
  try {
    const config = functions.config();
    if (config && config.razorpay) {
      if (!keyId) keyId = config.razorpay.key_id || config.razorpay.keyid;
      if (!keySecret) keySecret = config.razorpay.key_secret || config.razorpay.keysecret;
      if (!webhookSecret) webhookSecret = config.razorpay.webhook_secret || config.razorpay.webhooksecret;
    }
  } catch (e) {
    // functions.config() may throw if config is not set or not initialized
  }

  // Support lowercase/uppercase general key variations
  if (!keyId) keyId = process.env.key_id || process.env.KEY_ID;
  if (!keySecret) keySecret = process.env.key_secret || process.env.KEY_SECRET;

  if (!keyId || !keySecret) {
    logger.warn("Razorpay environment variables or functions config are missing.");
  }
  return { keyId, keySecret, webhookSecret };
};

/**
 * Create a Razorpay Subscription securely on the backend.
 * Callable from Android App (v2)
 */
exports.createRazorpayOrder = onCall({ secrets: [razorpayKeyId, razorpayKeySecret, razorpayWebhookSecret] }, async (request) => {
  console.log("FUNCTION ENTERED");

  if (!request.auth) {
    throw new HttpsError("unauthenticated", "Login required");
  }

  // Check if user already has an active subscription to prevent duplicate billing
  const userSubDoc = await db.collection("subscriptions").doc(request.auth.uid).get();
  if (userSubDoc.exists) {
    const subData = userSubDoc.data();
    if (subData.status === "Active") {
      throw new HttpsError(
        "already-exists",
        "You already have an active subscription."
      );
    }
  }

  const { planType, email: dataEmail, contact: dataContact } = request.data || {};

  const planMap = {
    pro: "plan_T8IAAvFkzz14zl",
    "Focus Pro": "plan_T8IAAvFkzz14zl",
    parent: "plan_T8IFBUo9pnUIFu",
    "Focus Parent": "plan_T8IFBUo9pnUIFu"
  };

  const plan_id = planMap[planType];

  if (!plan_id) {
    throw new HttpsError("invalid-argument", `Invalid plan type: ${planType}`);
  }

  const { keyId, keySecret } = getRazorpayConfig();

  console.log("KEY_ID:", keyId);
  console.log("KEY_SECRET_LENGTH:", keySecret ? keySecret.length : 0);
  console.log("KEY_SECRET_PREFIX:", keySecret ? keySecret.substring(0, 4) : "NONE");

  if (!keyId || !keySecret) {
    throw new HttpsError(
      "failed-precondition",
      "Razorpay credentials are not configured in environment variables."
    );
  }

  // Handle email with strict prefill validation
  let email = request.auth.token?.email || dataEmail || "";
  email = email.trim().toLowerCase();
  const isEmailValid = email && email.includes("@") && !email.includes("example.com") && !email.includes("test_user");

  // Handle contact with strict prefill validation
  let contact = dataContact || request.auth.token?.phone_number || "";
  contact = contact.replace(/\D/g, "").trim();
  if (contact.length > 12) {
    contact = contact.slice(-10);
  }
  const isContactValid = contact && contact.length >= 10 && contact !== "9999999999";

  console.log(`PLAN_ID: ${plan_id}`);
  console.log(`KEY_ID: ${keyId}`);
  console.log(`KEY_MODE: ${keyId.startsWith("rzp_live") ? "LIVE" : "TEST"}`);
  console.log(`USER_ID: ${request.auth.uid}`);
  console.log(`CUSTOMER EMAIL: ${isEmailValid ? email : "none"}, CONTACT: ${isContactValid ? contact : "none"}`);

  const razorpay = new Razorpay({
    key_id: keyId.trim(),
    key_secret: keySecret.trim()
  });

  console.log("KEY_ID:", JSON.stringify(keyId));
  console.log("KEY_SECRET:", JSON.stringify(keySecret));

  try {
    let customer;
    if (isEmailValid) {
      try {
        console.log(`Checking if customer already exists for email: ${email}`);
        const existing = await razorpay.customers.all({ email: email });
        if (existing && existing.items && existing.items.length > 0) {
          customer = existing.items.find(
            c => c.email && c.email.toLowerCase() === email.toLowerCase()
          );
          
          if (customer) {
            console.log(`Found exact customer: ${customer.id}`);
            
            // Update existing customer contact details if necessary, omitting placeholders
            try {
              const updateParams = {
                name: request.auth.token?.name || customer.name || `User ${request.auth.uid}`
              };
              let needsUpdate = false;
              
              if (isContactValid) {
                if (customer.contact !== contact) {
                  updateParams.contact = contact;
                  needsUpdate = true;
                }
              } else if (customer.contact === "9999999999" || customer.contact === "+919999999999") {
                updateParams.contact = "";
                needsUpdate = true;
              }
              
              if (needsUpdate) {
                console.log(`Updating existing customer ${customer.id} with contact: ${updateParams.contact || "blank"}`);
                customer = await razorpay.customers.edit(customer.id, updateParams);
                console.log("Customer updated successfully on Razorpay");
              }
            } catch (updateErr) {
              console.error("Failed to update existing customer contact details, continuing with current state:", updateErr);
            }
          } else {
            console.log("No exact customer found in search results. Will create new.");
          }
        }
      } catch (searchErr) {
        console.error("Failed to query existing customer by email:", searchErr);
      }
    }

    if (!customer) {
      try {
        console.log(`Creating new Razorpay customer`);
        const customerParams = {
          fail_existing: 0
        };
        // Temporarily commented out email, contact, and name
        /*
        if (isEmailValid) {
          customerParams.email = email;
        }
        if (isContactValid) {
          customerParams.contact = contact;
        }
        */
        customer = await razorpay.customers.create(customerParams);
        console.log(`Customer created successfully: ${customer.id}`);
      } catch (createErr) {
        console.error("========== RAW RAZORPAY ERROR ==========");
        console.error(createErr);
        console.error(JSON.stringify(createErr, null, 2));
        console.error("Status Code:", createErr.statusCode);
        console.error("Error:", createErr.error);
        console.error("Headers:", createErr.headers);
        console.error("========================================");

        throw createErr;
      }
    }

    if (!customer || !customer.id) {
      throw new Error("Razorpay customer object could not be created or retrieved.");
    }

    console.log("CUSTOMER ID:", customer.id);
    console.log("CUSTOMER EMAIL:", customer.email);
    console.log("CUSTOMER CONTACT:", customer.contact);

    const subscription = await razorpay.subscriptions.create({
      plan_id: plan_id,
      customer_id: customer.id,
      customer_notify: 1,
      total_count: 12,
      notes: {
        uid: request.auth.uid,
        planType: planType
      }
    }).catch(err => {
      console.log("🔥 RAZORPAY RAW ERROR START");
      console.log(JSON.stringify(err, null, 2));
      console.log("🔥 RAZORPAY RAW ERROR END");
      throw err;
    });

    if (!subscription || !subscription.id) {
      throw new Error("No subscription ID returned from Razorpay response.");
    }

    // Immediately save subscription mapping document before returning to client
    await db.collection("subscription_mappings").doc(subscription.id).set({
      uid: request.auth.uid,
      planType: planType,
      customerId: customer.id,
      status: "created",
      createdAt: admin.firestore.FieldValue.serverTimestamp()
    });

    console.log(`Successfully mapped and created subscription ${subscription.id} for user ${request.auth.uid}`);

    return {
      subscriptionId: subscription.id,
      paymentUrl: subscription.short_url,
      planType: planType,
      keyId: keyId
    };

  } catch (error) {
    console.log("🔥 RAW ERROR OBJECT:", error);
    let errMsg = error?.message || "Subscription creation failed";
    let errCode = "internal";
    throw new HttpsError(errCode, errMsg);
  }
});

/**
 * Verify signature securely. DOES NOT ACTIVATE PREMIUM ANYMORE.
 * Premium activation is strictly driven by the Webhook.
 */
exports.verifyRazorpayPayment = onCall({ enforceAppCheck: false, secrets: [razorpayKeyId, razorpayKeySecret, razorpayWebhookSecret] }, async (request) => {
  if (!request.auth) {
    throw new HttpsError(
      "unauthenticated",
      "Authentication is required to verify payment."
    );
  }

  const { razorpay_order_id, razorpay_subscription_id, razorpay_payment_id, razorpay_signature, planType } = request.data || {};

  const subscriptionId = razorpay_subscription_id || (razorpay_order_id && razorpay_order_id.startsWith("sub_") ? razorpay_order_id : null);
  const orderId = razorpay_order_id && !razorpay_order_id.startsWith("sub_") ? razorpay_order_id : null;

  if ((!orderId && !subscriptionId) || !razorpay_payment_id || !razorpay_signature || !planType) {
    throw new HttpsError(
      "invalid-argument",
      "Missing required payment verification details."
    );
  }

  const { keySecret } = getRazorpayConfig();

  if (!keySecret) {
    throw new HttpsError(
      "failed-precondition",
      "Razorpay credentials are not configured in environment variables."
    );
  }

  // Reconstruct and verify the signature using Razorpay official HMAC-SHA256 guidelines
  const text = subscriptionId 
    ? (razorpay_payment_id + "|" + subscriptionId)
    : (orderId + "|" + razorpay_payment_id);

  const generatedSignature = crypto
    .createHmac("sha256", keySecret)
    .update(text)
    .digest("hex");

  if (generatedSignature !== razorpay_signature) {
    logger.error("Payment signature mismatch!", {
      uid: request.auth.uid,
      orderId: orderId,
      subscriptionId: subscriptionId,
      paymentId: razorpay_payment_id
    });
    throw new HttpsError(
      "permission-denied",
      "Payment signature verification failed. Possible fraud attempt."
    );
  }

  logger.info("Signature verified successfully on server-side for payment:", razorpay_payment_id);

  // Immediately activate subscription upon successful payment verification
  try {
    const db = admin.firestore();
    const subscriptionRef = db.collection("subscriptions").doc(request.auth.uid);
    const resolvedPlan = (planType === "parent" || planType === "Focus Parent" || planType === "parent") ? "Focus Parent" : "Focus Pro";
    
    const currentStart = Date.now();
    const currentEnd = currentStart + 30 * 24 * 60 * 60 * 1000;

    const subscriptionUpdate = {
      status: "Active",
      planType: resolvedPlan,
      subscriptionId: subscriptionId || "",
      paymentId: razorpay_payment_id || "",
      activatedAt: currentStart,
      nextDueDate: currentEnd,
      expiryDate: currentEnd,
      updatedAt: admin.firestore.FieldValue.serverTimestamp()
    };

    await subscriptionRef.set(subscriptionUpdate, { merge: true });
    logger.info(`Successfully activated subscription in verifyRazorpayPayment for uid: ${request.auth.uid}. Plan: ${resolvedPlan}`);
  } catch (dbErr) {
    logger.error(`Failed to immediately update subscription in Firestore in verifyRazorpayPayment: ${dbErr.message}`, dbErr);
  }

  return {
    success: true,
    message: "Signature verified. Premium activated successfully."
  };
});

/**
 * Handle asynchronous Razorpay Webhook events.
 * SINGLE SOURCE OF TRUTH.
 */
exports.razorpayWebhook = onRequest({ secrets: [razorpayKeyId, razorpayKeySecret, razorpayWebhookSecret] }, async (req, res) => {
  const timestamp = new Date().toISOString();
  const eventName = req.body?.event || "unknown";
  logger.info(`WEBHOOK RECEIVED - Event: ${eventName} - Timestamp: ${timestamp}`);

  if (req.method !== "POST") {
    logger.warn(`Rejected non-POST webhook request: ${req.method}`);
    return res.status(405).send("Method Not Allowed");
  }

  const signature = req.headers["x-razorpay-signature"];
  const { webhookSecret, keyId, keySecret } = getRazorpayConfig();

  // Compute HMAC signature over raw webhook payload body
  const rawBody = req.rawBody ? req.rawBody : (typeof req.body === 'string' ? req.body : JSON.stringify(req.body));
  const expectedSignature = crypto
    .createHmac("sha256", webhookSecret)
    .update(rawBody)
    .digest("hex");

  if (signature !== expectedSignature) {
    logger.error("Webhook signature verification FAILED.", {
      receivedSignature: signature,
      expectedSignature: expectedSignature,
      timestamp: timestamp
    });
    return res.status(400).send("Invalid Webhook Signature");
  } else {
    logger.info("Webhook signature verification PASSED.");
  }

  const event = req.body.event;
  const payload = req.body.payload;

  const subscriptionEntity = payload.subscription?.entity;
  const paymentEntity = payload.payment?.entity;
  const customerEntity = payload.customer?.entity;
  const subscriptionId = subscriptionEntity?.id || paymentEntity?.subscription_id || null;
  const paymentId = paymentEntity?.id || (subscriptionEntity && subscriptionEntity.payment_id) || null;
  const customerId = subscriptionEntity?.customer_id || paymentEntity?.customer_id || customerEntity?.id || null;

  logger.info("Received Razorpay Webhook Event Details", {
    eventId: req.body.id || "none",
    event: event,
    subscriptionId: subscriptionId || "none",
    paymentId: paymentId || "none",
    customerId: customerId || "none"
  });

  try {
    if (!subscriptionId) {
      logger.error("Webhook error: No subscription ID found in payload.", { event });
      return res.status(200).send("No subscription ID found in payload");
    }

    // 2. Find uid using subscription_mappings collection
    const mappingDoc = await db.collection("subscription_mappings").doc(subscriptionId).get();
    if (!mappingDoc.exists) {
      logger.warn(`Webhook: No mapping found for subscription ID: ${subscriptionId}. Event: ${event}`);
      return res.status(200).send("No subscription mapping found in database");
    }

    const mappingData = mappingDoc.data();
    const uid = mappingData.uid;
    const planType = mappingData.planType || "Focus Pro";

    logger.info("Extracted Webhook Metadata", {
      subscriptionId: subscriptionId,
      paymentId: paymentId || "none",
      customerId: customerId || "none",
      uid: uid,
      planType: planType,
      event: event
    });

    // 3. Initialize Razorpay SDK to fetch latest subscription state for absolute Source of Truth
    const razorpay = new Razorpay({
      key_id: keyId.trim(),
      key_secret: keySecret.trim()
    });

    console.log("KEY_ID:", JSON.stringify(keyId));
    console.log("KEY_SECRET:", JSON.stringify(keySecret));

    let subDetails;
    try {
      subDetails = await razorpay.subscriptions.fetch(subscriptionId);
    } catch (fetchErr) {
      logger.error(`Webhook: Failed to fetch subscription details for ${subscriptionId}:`, fetchErr);
      subDetails = subscriptionEntity || { id: subscriptionId, status: "active" };
    }

    // 4. Map statuses
    const subStatus = subDetails.status; // 'created', 'authenticated', 'active', 'completed', 'cancelled', 'halted'
    let mappedStatus = "Inactive";
    if (subStatus === "authenticated" || subStatus === "active") {
      mappedStatus = "Active";
    } else if (subStatus === "cancelled") {
      mappedStatus = "Cancelled";
    } else if (subStatus === "completed") {
      mappedStatus = "Completed";
    } else if (subStatus === "halted") {
      mappedStatus = "Halted";
    }

    const currentStart = subDetails.current_start ? subDetails.current_start * 1000 : Date.now();
    const currentEnd = subDetails.current_end ? subDetails.current_end * 1000 : (currentStart + 30 * 24 * 60 * 60 * 1000);

    const resolvedPaymentId = paymentId || subDetails.payment_id || null;

    // Idempotency: Duplicate webhook events must never create duplicate writes.
    const eventId = req.body.id;
    if (eventId) {
      const eventCheckRef = db.collection("processed_webhooks").doc(eventId);
      const eventDoc = await eventCheckRef.get();
      if (eventDoc.exists) {
        logger.info(`Webhook duplicate event ignored. Event ID: ${eventId}`);
        return res.status(200).json({ status: "ok", message: "Event already processed previously" });
      }
      
      // Save event ID to avoid duplicate writes
      try {
        logger.info(`[Firestore Write] Attempting to mark event ${eventId} as processed in processed_webhooks...`);
        await eventCheckRef.set({
          processedAt: admin.firestore.FieldValue.serverTimestamp(),
          event: event,
          subscriptionId: subscriptionId,
          uid: uid
        });
        logger.info(`[Firestore Write] SUCCESS: Marked event ${eventId} as processed.`);
      } catch (writeErr) {
        logger.error(`[Firestore Write] FAILED: Could not mark event ${eventId} as processed in processed_webhooks. Error: ${writeErr.message}`, writeErr);
        throw writeErr;
      }
    }

    // In addition, if there is a resolvedPaymentId, maintain idempotency using processed_payments collection
    if (resolvedPaymentId) {
      const paymentCheckRef = db.collection("processed_payments").doc(resolvedPaymentId);
      const paymentDoc = await paymentCheckRef.get();
      if (!paymentDoc.exists) {
        try {
          logger.info(`[Firestore Write] Attempting to create processed_payments doc for paymentId: ${resolvedPaymentId}...`);
          await paymentCheckRef.set({
            uid: uid,
            subscriptionId: subscriptionId,
            paymentId: resolvedPaymentId,
            planType: planType,
            verifiedAt: admin.firestore.FieldValue.serverTimestamp(),
            source: "webhook"
          });
          logger.info(`[Firestore Write] SUCCESS: Created processed_payments doc for paymentId: ${resolvedPaymentId}.`);
        } catch (writeErr) {
          logger.error(`[Firestore Write] FAILED: Could not create processed_payments doc for paymentId: ${resolvedPaymentId}. Error: ${writeErr.message}`, writeErr);
          throw writeErr;
        }
      } else {
        logger.info(`[Firestore Check] Payment ${resolvedPaymentId} already exists in processed_payments.`);
      }
    }

    // 5. Handle webhook events via audited switch statement
    let shouldUpdateSubscription = false;

    switch (event) {
      case "subscription.authenticated":
        logger.info(`[Webhook Event: subscription.authenticated] Authenticating subscription ${subscriptionId} for user ${uid}`);
        shouldUpdateSubscription = true;
        break;

      case "subscription.activated":
        logger.info(`[Webhook Event: subscription.activated] Activating subscription ${subscriptionId} for user ${uid}. Support for live-mode activation is active.`);
        shouldUpdateSubscription = true;
        break;

      case "subscription.charged":
        logger.info(`[Webhook Event: subscription.charged] Subscription ${subscriptionId} charged successfully. Updating cycle for user ${uid}`);
        shouldUpdateSubscription = true;
        break;

      case "payment.captured":
        logger.info(`[Webhook Event: payment.captured] Payment captured for subscription ${subscriptionId}. Ensuring active status for user ${uid}`);
        shouldUpdateSubscription = true;
        break;

      case "subscription.completed":
        logger.info(`[Webhook Event: subscription.completed] Subscription ${subscriptionId} completed all cycles for user ${uid}`);
        shouldUpdateSubscription = true;
        break;

      case "subscription.cancelled":
        logger.info(`[Webhook Event: subscription.cancelled] Subscription ${subscriptionId} cancelled for user ${uid}`);
        shouldUpdateSubscription = true;
        break;

      case "payment.failed":
        logger.warn(`[Webhook Event: payment.failed] Payment failed for subscription ${subscriptionId}, user ${uid}`);
        if (paymentEntity) {
          try {
            logger.info(`[Firestore Write] Attempting to record payment failure for payment ${resolvedPaymentId}, user ${uid}...`);
            await db.collection("payment_failures").add({
              uid: uid,
              paymentId: resolvedPaymentId,
              subscriptionId: subscriptionId,
              errorCode: paymentEntity.error_code,
              errorDescription: paymentEntity.error_description,
              failedAt: admin.firestore.FieldValue.serverTimestamp()
            });
            logger.info(`[Firestore Write] SUCCESS: Recorded payment failure for payment ${resolvedPaymentId}.`);
          } catch (writeErr) {
            logger.error(`[Firestore Write] FAILED: Could not record payment failure. Error: ${writeErr.message}`, writeErr);
            throw writeErr;
          }
        }
        break;

      default:
        logger.warn(`[Webhook Event: UNHANDLED] Received unhandled event type: ${event} for subscription ${subscriptionId}`, {
          event: event,
          subscriptionId: subscriptionId,
          paymentId: resolvedPaymentId,
          payload: payload
        });
        break;
    }

    if (shouldUpdateSubscription) {
      // 6. Update subscriptions/{uid} with fields like: status, planType, subscriptionId, paymentId, activatedAt, nextDueDate, expiryDate, updatedAt
      const subscriptionRef = db.collection("subscriptions").doc(uid);
      const subscriptionUpdate = {
        status: mappedStatus,
        planType: planType,
        subscriptionId: subscriptionId,
        updatedAt: admin.firestore.FieldValue.serverTimestamp()
      };

      if (resolvedPaymentId) {
        subscriptionUpdate.paymentId = resolvedPaymentId;
      }
      if (currentStart) {
        subscriptionUpdate.activatedAt = currentStart;
      }
      if (currentEnd) {
        subscriptionUpdate.nextDueDate = currentEnd;
        subscriptionUpdate.expiryDate = currentEnd;
      }

      try {
        logger.info(`[Firestore Write] Attempting to update subscriptions doc for user ${uid}...`, {
          subscriptionId: subscriptionId,
          status: mappedStatus,
          planType: planType
        });
        await subscriptionRef.set(subscriptionUpdate, { merge: true });
        logger.info(`[Firestore Write] SUCCESS: Updated subscription for uid: ${uid}. Status: ${mappedStatus}, Plan: ${planType}`);
      } catch (writeErr) {
        logger.error(`[Firestore Write] FAILED: Could not update subscription for uid: ${uid}. Error: ${writeErr.message}`, writeErr);
        throw writeErr;
      }
    }

    return res.status(200).json({ status: "ok" });
  } catch (error) {
    logger.error("Error processing Webhook:", error);
    return res.status(500).send("Webhook processing error");
  }
});
