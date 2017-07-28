import java.util.UUID

object ErrorHandling {
  type Error = String
}

import ErrorHandling._

final case class EventStore(
  appendToStream: (String, Int, List[Event]) => Either[Error, Unit],
  readFromStream: String => Either[Error, List[Event]])

sealed trait Account
case object Uninitialized extends Account
final case class OnlineAccount(accountId: UUID, balance: BigDecimal) extends Account

sealed trait Command
final case class CreateOnlineAccount(accountId: UUID) extends Command
final case class MakeDeposit(accountId: UUID, depositAmount: BigDecimal) extends Command
final case class Withdraw(accountId: UUID, withdrawalAmount: BigDecimal) extends Command
final case class MakeTransaction(accountId: UUID, transferAmount: BigDecimal, destAccount: UUID) extends Command
final case class TransactionDepositTargetAccount(accountId: UUID, transferAmount: BigDecimal, srcAccount: UUID) extends Command

sealed trait Event
final case class OnlineAccountCreated(accountId: UUID) extends Event
final case class DepositMade(accountId: UUID, depositAmount: BigDecimal) extends Event
final case class MoneyWithdrawn(accountId: UUID, withdrawalAmount: BigDecimal) extends Event
final case class TransactionAccountDebited(accountId: UUID, amount: BigDecimal, destAccount: UUID) extends Event
final case class TransactionAccountDeposited(accountId: UUID, amount: BigDecimal, srcAccount: UUID) extends Event

object Domain {

  private def apply(account: Account, event: Event): Account = {
    (account, event) match {
      case (Uninitialized, OnlineAccountCreated(accountId)) =>
        OnlineAccount(accountId, 0)
      case (OnlineAccount(accountId, balance), DepositMade(_, depositAmount)) =>
        OnlineAccount(accountId, balance + depositAmount)
      case (OnlineAccount(accountId, balance), MoneyWithdrawn(_, withdrawalAmount)) =>
        OnlineAccount(accountId, balance - withdrawalAmount)
      case (OnlineAccount(accountId, balance), TransactionAccountDebited(_, amount, _)) =>
        OnlineAccount(accountId, balance - amount)
      case (OnlineAccount(accountId, balance), TransactionAccountDeposited(_, amount, _)) =>
        OnlineAccount(accountId, balance + amount)
      case _ => account
    }
  }

  def replay(initial: Account, events: List[Event]): (Account, Int) = {
    events.foldLeft((initial, -1)) {
      case ((state, version), event) => (apply(state, event), version + 1)
    }
  }

  def decide(cmd: Command, state: Account): Either[Error, List[Event]] = {
    (state, cmd) match {
      case (Uninitialized, CreateOnlineAccount(accountId)) =>
        Right(List(OnlineAccountCreated(accountId)))
      case (OnlineAccount(accountId, balance), MakeDeposit(_, amount)) =>
        if (amount <= 0) {
          Left("deposit amount must be positive")
        } else {
          Right(List(DepositMade(accountId, amount)))
        }
      case (OnlineAccount(accountId, balance), Withdraw(_, amount)) =>
        if (amount <= 0) {
          Left("withdrawal amount must be positive")
        } else if (balance - amount < 0) {
          Left("overdraft not allowed")
        } else {
          Right(List(MoneyWithdrawn(accountId, amount)))
        }
      case (OnlineAccount(accountId, balance), MakeTransaction(_, amount, destAccount)) =>
        if (amount <= 0) {
          Left("transaction amount must be positive")
        } else if (balance - amount < 0) {
          Left("overdraft not allowed")
        } else {
          Right(List(TransactionAccountDebited(accountId, amount, destAccount)))
        }
      case (OnlineAccount(accountId, balance), TransactionDepositTargetAccount(_, amount, destAccount)) =>
        if (amount <= 0) {
          Left("transaction amount must be positive")
        } else {
          Right(List(TransactionAccountDeposited(accountId, amount, destAccount)))
        }
      case _ =>
        Left(s"invalid operation $cmd on current state $state")
    }
  }
}

object CommandHandling {
  import cats._
  import cats.data._
  import cats.implicits._

  def handleCommand(store: EventStore, processManager: Event => List[(UUID, Command)])
                   (accountId: UUID, cmd: Command) : Either[Error, Unit] = {
    for {
      events <- store.readFromStream(accountId.toString)
      (state, version) = Domain.replay(Uninitialized, events)
      newEvents <- Domain.decide(cmd, state)
      _ <- store.appendToStream(accountId.toString, version, newEvents)
      commands = newEvents.flatMap(e => processManager(e))
      _ <- commands.map{ case (id, c) => handleCommand(store, processManager)(id, c) }.sequenceU
    } yield ()
  }
}

object ProcessManager {
  def process(event: Event): List[(UUID, Command)] = {
    event match {
      case TransactionAccountDebited(srcAccount, amount, destAccount) =>
        List((destAccount, TransactionDepositTargetAccount(destAccount, amount, srcAccount)))
      case _ => Nil
    }
  }
}
