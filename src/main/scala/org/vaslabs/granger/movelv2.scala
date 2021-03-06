package org.vaslabs.granger

import java.time.format.DateTimeFormatter
import java.time.{Clock, LocalDate, ZonedDateTime}

import io.circe.java8.time
import io.circe.{Decoder, Encoder, KeyDecoder, KeyEncoder}
import org.vaslabs.granger.comms.api.model.Activity
import org.vaslabs.granger.modeltreatments.{
  RepeatRootCanalTreatment,
  RootCanalTreatment,
  TreatmentCategory
}
import org.vaslabs.granger.modelv2._

/**
  * Created by vnicolaou on 09/07/17.
  */
object v2json {
  import io.circe.generic.semiauto._
  import io.circe.generic.auto._
  val localDateFormatter = DateTimeFormatter.ISO_DATE
  val zonedDateTimeFormatter = DateTimeFormatter.ISO_ZONED_DATE_TIME

  implicit val localDateEncoder: Encoder[LocalDate] =
    time.encodeLocalDate(localDateFormatter)
  implicit val localDateDecoder: Decoder[LocalDate] =
    time.decodeLocalDate(localDateFormatter)

  implicit val zonedDateTimeEncoder: Encoder[ZonedDateTime] =
    time.encodeZonedDateTime(zonedDateTimeFormatter)
  implicit val zonedDateTimeDecoder: Decoder[ZonedDateTime] =
    time.decodeZonedDateTime(zonedDateTimeFormatter)

  implicit val decoder: Decoder[TreatmentCategory] = Decoder[String].emap(s =>
    s match {
      case "RCT" => Right(RootCanalTreatment())
      case "Re-RCT" => Right(RepeatRootCanalTreatment())
      case _ => Left(s)
  })
  implicit val encoder: Encoder[TreatmentCategory] =
    Encoder[String].contramap[TreatmentCategory](
      tc =>
        tc match {
          case _: RootCanalTreatment => "RCT"
          case _: RepeatRootCanalTreatment => "Re-RCT"
      }
    )

  private[this] def isNullOrEmpty(name: String): Boolean =
    name == null || name.isEmpty

  def verifyNonEmptyString[A](value: String, a: A): Either[String, A] = {
    if (isNullOrEmpty(value))
      Left("Value is empty")
    else
      Right(a)
  }

  implicit val medicamentDecoder: Decoder[Medicament] =
    deriveDecoder[Medicament].emap(
      medicament =>
          verifyNonEmptyString(medicament.name, medicament)
    )

  implicit val patientIdEncoder: Encoder[PatientId] =
    Encoder[Long].contramap(_.id)
  implicit val patientIdDecoder: Decoder[PatientId] =
    Decoder[Long].map(PatientId(_))

  implicit val patientIdKeyDecoder: KeyDecoder[PatientId] =
    KeyDecoder[Long].map(PatientId(_))
  implicit val patientIdKeyEncoder: KeyEncoder[PatientId] =
    KeyEncoder[Long].contramap[PatientId](_.id)
  implicit val treatmentNoteDecoder: Decoder[TreatmentNote] =
    deriveDecoder[TreatmentNote].emap(
      tn =>
          verifyNonEmptyString[TreatmentNote](tn.note, tn)
    )

  implicit val nextVisitDecoder: Decoder[NextVisit] =
    deriveDecoder[NextVisit].emap(
      nv => verifyNonEmptyString[NextVisit](nv.notes, nv)
    )

  implicit val patientEncoder: Encoder[Patient] = deriveEncoder[Patient]
  implicit val patientDecoder: Decoder[Patient] = deriveDecoder[Patient]
}

object modelv2 {
  import org.vaslabs.granger.modeltreatments.{
    RepeatRootCanalTreatment,
    RootCanalTreatment,
    TreatmentCategory
  }

  import org.vaslabs.granger.comms.api.model.Activity._
  import v2json._

  final class PatientId(val id: Long) extends AnyVal {
    override def toString: String = id.toString
  }

  object PatientId {
    @inline def apply(id: Long): PatientId = new PatientId(id)
  }

  case class TreatmentNote(note: String, dateOfNote: ZonedDateTime)

  case class Treatment(dateStarted: ZonedDateTime,
                       dateCompleted: Option[ZonedDateTime] = None,
                       category: TreatmentCategory,
                       roots: List[Root] = List.empty,
                       notes: List[TreatmentNote] = List.empty,
                       medicaments: List[Medicament] = List.empty,
                       nextVisits: List[NextVisit] = List.empty,
                       obturation: Option[List[Root]] = Some(List.empty)) {
    def update(roots: Option[List[Root]],
               note: Option[TreatmentNote],
               medicament: Option[Medicament],
               nextVisit: Option[NextVisit],
               obturation: Option[List[Root]]): Treatment = {
      val newNotes = note.map(_ :: notes).getOrElse(notes)
      val newMedicaents =
        medicament.map(_ :: medicaments).getOrElse(medicaments)
      val newNextVisits = nextVisit.map(_ :: nextVisits).getOrElse(nextVisits)
      val newRoots = roots.getOrElse(this.roots)
      val newObturation = obturation.getOrElse(this.obturation.getOrElse(List.empty))
      copy(roots = newRoots,
           notes = newNotes,
           medicaments = newMedicaents,
           nextVisits = newNextVisits,
           obturation = Some(newObturation))
    }
  }

  case class Tooth(number: Int, treatments: List[Treatment] = List.empty) {

    def update(roots: Option[List[Root]],
               medicament: Option[Medicament],
               nextVisit: Option[NextVisit],
               treatmentNote: Option[TreatmentNote], startedTreatmentTimestamp: ZonedDateTime,
               obturation: Option[List[Root]]): Tooth = {
      treatments
        .filter(t => t.dateStarted.equals(startedTreatmentTimestamp))
        .map(_.update(roots, treatmentNote, medicament, nextVisit, obturation))
        .headOption
        .map(t => Tooth(number, t :: treatments.filterNot(_.dateStarted == startedTreatmentTimestamp)))
        .getOrElse(this)
    }

    def update(treatment: Treatment): Either[Treatment, Tooth] = {
      if (treatments.size == 0)
        Right(copy(treatments = List(treatment)))
      else
        treatments.head.dateCompleted
          .map(_ => Right(copy(treatments = treatment :: treatments)))
          .getOrElse(Left(treatments.head))
    }

    def finishTreatment()(implicit clock: Clock): Option[Tooth] = {
      treatments.headOption
        .flatMap(
          treatment =>
            treatment.dateCompleted.fold[Option[Treatment]](Some(
              treatment.copy(dateCompleted = Some(ZonedDateTime.now(clock)))))(
              _ => None)
        )
        .map(
          t => {
            t :: treatments.drop(1)
          }
        )
        .map(ts => copy(treatments = ts))
    }

    implicit val t_transformer: Transformer[Treatment] = (t: Treatment) => {
      val date = t.dateCompleted.getOrElse(t.dateStarted)
      val note = t.dateCompleted
        .map(_ => s"Finished treatment ${t.category.toString}")
        .getOrElse(s"Started treatment: ${t.category.toString}")
      Activity(date, number, note)
    }

    def allActivity(): List[Activity] = {
      val treatmentsActivity: List[Activity] = treatments.map(_.asActivity())
      treatmentsActivity
    }

  }

  object Tooth {
    implicit val ordering: Ordering[Tooth] = (x: Tooth, y: Tooth) => {
      if (x.number <= 18 && y.number <= 18)
        y.number - x.number
      else if (x.number <= 18)
        -1
      else if (y.number <= 18)
        1
      else if (x.number <= 28 && y.number <= 28)
        x.number - y.number
      else if (x.number <= 28)
        -1
      else if (y.number <= 28)
        1
      else if (x.number <= 38 && y.number <= 38)
        x.number - y.number
      else if (y.number <= 38)
        -1
      else if (x.number <= 38)
        1
      else
        y.number - x.number
    }
  }

  case class NextVisit(notes: String,
                       dateOfNextVisit: ZonedDateTime,
                       dateOfNote: ZonedDateTime)

  case class Root(name: String, length: Int, size: String)

  case class Medicament(name: String, date: ZonedDateTime)

  case class Patient(patientId: PatientId,
                     firstName: String,
                     lastName: String,
                     dateOfBirth: LocalDate,
                     dentalChart: DentalChart) {
    def extractLatestActivity: Map[Int, List[Activity]] = {
      dentalChart.teeth
        .flatMap(_.allActivity())
        .groupBy(_.tooth)
        .mapValues(_.sorted)
    }

    def update(tooth: Tooth): Patient =
      copy(dentalChart = dentalChart.update(tooth))

    def update(patientId: PatientId): Patient = {
      copy(patientId, dentalChart = DentalChart.emptyChart())
    }
  }

  case class DentalChart(teeth: List[Tooth]) {

    def update(tooth: Tooth): DentalChart = {
      DentalChart((tooth :: teeth.filterNot(_.number == tooth.number)).sorted)
    }

  }

  object DentalChart {
    def emptyChart(): DentalChart =
      DentalChart(
        ((11 to 18) ++ (21 to 28) ++ (31 to 38) ++ (41 to 48))
          .map(
            Tooth(_)
          )
          .toList
          .sorted)
  }

}
