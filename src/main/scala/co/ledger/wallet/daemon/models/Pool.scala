package co.ledger.wallet.daemon.models

import co.ledger.core
import co.ledger.core.implicits._
import co.ledger.wallet.daemon.async.MDCPropagatingExecutionContext
import co.ledger.wallet.daemon.clients.ClientFactory
import co.ledger.wallet.daemon.configurations.DaemonConfiguration
import co.ledger.wallet.daemon.database.PoolDto
import co.ledger.wallet.daemon.exceptions.CurrencyNotFoundException
import co.ledger.wallet.daemon.libledger_core.async.LedgerCoreExecutionContext
import co.ledger.wallet.daemon.libledger_core.crypto.SecureRandomRNG
import co.ledger.wallet.daemon.libledger_core.debug.NoOpLogPrinter
import co.ledger.wallet.daemon.libledger_core.filesystem.ScalaPathResolver
import co.ledger.wallet.daemon.schedulers.observers.SynchronizationResult
import co.ledger.wallet.daemon.services.LogMsgMaker
import co.ledger.wallet.daemon.utils.{HexUtils, Utils}
import com.fasterxml.jackson.annotation.JsonProperty
import com.twitter.inject.Logging
import org.bitcoinj.core.Sha256Hash
import Wallet._
import co.ledger.core.ConfigurationDefaults

import scala.collection.JavaConverters._
import scala.collection._
import scala.concurrent.{ExecutionContext, Future}

class Pool(private val coreP: core.WalletPool, val id: Long) extends Logging {
  private[this] val self = this

  implicit val ec: ExecutionContext = MDCPropagatingExecutionContext.Implicits.global
  private val _coreExecutionContext = LedgerCoreExecutionContext.newThreadPool()
  private[this] val eventReceivers: mutable.Set[core.EventReceiver] = Utils.newConcurrentSet[core.EventReceiver]

  val name: String = coreP.getName

  def view: Future[WalletPoolView] = coreP.getWalletCount().map { count => WalletPoolView(name, count) }

  /**
    * Obtain wallets by batch size and offset.
    *
    * @param offset the offset the query starts from.
    * @param batch  the size of query.
    * @return a tuple of total wallet count and a sequence of wallets from offset to batch size.
    */
  def wallets(offset: Int, batch: Int): Future[(Int, Seq[core.Wallet])] = {
    assert(offset >= 0, s"offset invalid $offset")
    assert(batch > 0, "batch must be positive")
    coreP.getWalletCount().flatMap { count =>
      val size = batch min (count - offset)
      coreP.getWallets(offset, size).map { coreWs =>
        coreWs.asScala.toList
      }.map((count, _))
    }
  }

  private def startListen(wallet: core.Wallet): Future[core.Wallet] = {
    for {
      _ <- wallet.startCacheAndRealTimeObserver
    } yield wallet
  }

  /**
    * Obtain wallet by name. If the name doesn't exist in local cache, a core retrieval will be performed.
    *
    * @param walletName name of wallet.
    * @return a future of optional wallet.
    */
  def wallet(walletName: String): Future[Option[core.Wallet]] = {
    coreP.getWallet(walletName).map(Option(_)).recover {
      case _: co.ledger.core.implicits.WalletNotFoundException => None
    }
  }

  /**
    * Obtain currency by name.
    *
    * @param currencyName the specified currency name.
    * @return a future of optional currency.
    */
  def currency(currencyName: String): Future[Option[core.Currency]] =
    coreP.getCurrency(currencyName).map(Option.apply).recover {
      case _: core.implicits.CurrencyNotFoundException => None
    }

  /**
    * Obtain currencies.
    *
    * @return future of currencies sequence.
    */
  def currencies(): Future[Seq[core.Currency]] = {
    coreP.getCurrencies().map(_.asScala.toList)
  }

  /**
    * Clear the event receivers on this pool and underlying wallets. It will also call `stopRealTimeObserver` method.
    *
    * @return a future of Unit.
    */
  def clear: Future[Unit] = {
    Future.successful(stopRealTimeObserver()).map { _ =>
      unregisterEventReceivers()
    }
  }

  def addWalletIfNotExist(walletName: String, currencyName: String): Future[core.Wallet] = {
    coreP.getCurrency(currencyName).flatMap { coreC =>
      val walletConfig = core.DynamicObject.newInstance()
      val apiUrl = DaemonConfiguration.explorer.api.paths.get(coreC.getName) match {
        case Some(path) => path.host
        case None => ConfigurationDefaults.BLOCKCHAIN_DEFAULT_API_ENDPOINT
      }
      walletConfig.putString("BLOCKCHAIN_EXPLORER_API_ENDPOINT", apiUrl)
      val wsUrl = DaemonConfiguration.explorer.ws.getOrElse(
        coreC.getName,
        DaemonConfiguration.explorer.ws("default"))
      walletConfig.putString("BLOCKCHAIN_OBSERVER_WS_ENDPOINT", wsUrl)
      coreP.createWallet(walletName, coreC, walletConfig).flatMap { coreW =>
        info(LogMsgMaker.newInstance("Wallet created").append("name", walletName).append("pool_name", name).append("currency_name", currencyName).toString())
        startListen(coreW)
      }.recoverWith {
        case _: WalletAlreadyExistsException =>
          warn(LogMsgMaker.newInstance("Wallet already exist")
            .append("name", walletName)
            .append("pool_name", name)
            .append("currency_name", currencyName)
            .toString())
          coreP.getWallet(walletName).flatMap { coreW => startListen(coreW) }
      }
    }.recoverWith {
      case _: core.implicits.CurrencyNotFoundException => Future.failed(CurrencyNotFoundException(currencyName))
    }
  }

  /**
    * Subscribe specied event receiver to core pool, also save the event receiver to the local container.
    *
    * @param eventReceiver the event receiver object need to be registered.
    */
  def registerEventReceiver(eventReceiver: core.EventReceiver): Unit = {
    if (!eventReceivers.contains(eventReceiver)) {
      eventReceivers += eventReceiver
      coreP.getEventBus.subscribe(_coreExecutionContext, eventReceiver)
      debug(s"Register $eventReceiver")
    } else {
      debug(s"Already registered $eventReceiver")
    }
  }

  /**
    * Unsubscribe all event receivers for this pool, including empty the event receivers container in memory.
    *
    */
  def unregisterEventReceivers(): Unit = {
    eventReceivers.foreach { eventReceiver =>
      coreP.getEventBus.unsubscribe(eventReceiver)
      eventReceivers.remove(eventReceiver)
      debug(s"Unregister $eventReceiver")
    }
  }

  /**
    * Synchronize all accounts within this pool.
    *
    * @return a future of squence of synchronization results.
    */
  def sync(): Future[Seq[SynchronizationResult]] = {
    for {
      count <- coreP.getWalletCount()
      wallets <- coreP.getWallets(0, count)
      result <- Future.sequence(wallets.asScala.map { wallet => wallet.syncAccounts(name) }).map(_.flatten)
    } yield result
  }

  /**
    * Start real time observer of this pool will start the observers of the underlying wallets and accounts.
    *
    * @return a future of Unit.
    */
  def startRealTimeObserver(): Future[Unit] = {
    coreP.getWalletCount().map { count =>
      coreP.getWallets(0, count).map { coreWs =>
        coreWs.asScala.map { coreW => startListen(coreW) }
      }
    }
  }

  /**
    * Stop real time observer of this pool will stop the observers of the underlying wallets and accounts.
    *
    * @return a Unit.
    */
  def stopRealTimeObserver(): Unit = {
    debug(LogMsgMaker.newInstance("Stop real time observer").append("pool", name).toString())
    coreP.getWalletCount().map { count =>
      coreP.getWallets(0, count).map { coreWs =>
        coreWs.asScala.foreach { coreW => coreW.stopRealTimeObserver }
      }
    }
  }

  override def equals(that: Any): Boolean = {
    that match {
      case that: Pool => that.isInstanceOf[Pool] && self.hashCode == that.hashCode
      case _ => false
    }
  }

  override def hashCode: Int = {
    self.id.hashCode() + self.name.hashCode
  }

  override def toString: String = s"Pool(name: $name, id: $id)"
}

object Pool {
  def newInstance(coreP: core.WalletPool, id: Long): Pool = {
    new Pool(coreP, id)
  }

  def newCoreInstance(poolDto: PoolDto): Future[core.WalletPool] = {
    val poolConfig = core.DynamicObject.newInstance()
    //    poolConfig.putString("BLOCKCHAIN_OBSERVER_WS_ENDPOINT", "ws://notification.explorers.dev.aws.ledger.fr:9000/ws/{}")
    //    poolConfig.putString("BLOCKCHAIN_OBSERVER_ENGINE", "LEDGER_API")
    core.WalletPoolBuilder.createInstance()
      .setHttpClient(ClientFactory.httpClient)
      .setWebsocketClient(ClientFactory.webSocketClient)
      .setLogPrinter(new NoOpLogPrinter(ClientFactory.threadDispatcher.getMainExecutionContext, true))
      .setThreadDispatcher(ClientFactory.threadDispatcher)
      .setPathResolver(new ScalaPathResolver(corePoolId(poolDto.userId, poolDto.name)))
      .setRandomNumberGenerator(new SecureRandomRNG)
      .setDatabaseBackend(core.DatabaseBackend.getSqlite3Backend)
      .setConfiguration(poolConfig)
      .setName(poolDto.name)
      .build()
  }

  private def corePoolId(userId: Long, poolName: String): String = HexUtils.valueOf(Sha256Hash.hash(s"$userId:$poolName".getBytes))
}

case class WalletPoolView(
                           @JsonProperty("name") name: String,
                           @JsonProperty("wallet_count") walletCount: Int
                         )

