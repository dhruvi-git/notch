/** Raw analytics events, stored in MongoDB. */
import mongoose from "mongoose";

const Event = mongoose.model("Event", new mongoose.Schema({ name: String, at: Date, data: Object }));

export async function saveEvent(name: string, data: object): Promise<void> {
  await mongoose.connect(process.env.MONGO_URL ?? "mongodb://localhost:27017/events");
  await Event.create({ name, at: new Date(), data });
}
